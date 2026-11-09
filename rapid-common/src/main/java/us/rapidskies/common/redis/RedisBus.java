package us.rapidskies.common.redis;

import com.google.gson.Gson;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.slf4j.Logger;
import redis.clients.jedis.AbstractPipeline;
import redis.clients.jedis.Connection;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.RedisClient;
import redis.clients.jedis.Response;
import us.rapidskies.common.config.RedisSettings;

/**
 * Redis access for every rapid plugin: pub/sub between servers plus the few key/value
 * operations the registry and presence need.
 *
 * <p>All channels and keys are under {@code rapid:} so they never collide with LuckPerms,
 * which shares the Redis instance. One background thread holds a pattern subscription to
 * {@code rapid:*} and dispatches to handlers by channel, reconnecting with backoff if
 * Redis restarts; handlers run on that thread, so they must be quick or hand off.
 *
 * <p>Command methods block on a network round trip: call them off the main thread.
 * No Jedis type appears in this class's public API (see rapid-common/build.gradle.kts).
 */
public final class RedisBus implements AutoCloseable {

    public static final String PREFIX = "rapid:";

    private static final Gson GSON = new Gson();

    private final RedisClient client;
    private final Logger logger;
    private final Map<String, List<Consumer<String>>> handlers = new ConcurrentHashMap<>();
    private final Thread subscriber;
    private volatile JedisPubSub pubSub;
    private volatile boolean closed;

    private RedisBus(RedisClient client, Logger logger, String clientName) {
        this.client = client;
        this.logger = logger;
        this.subscriber = Thread.ofPlatform().daemon().name("rapid-redis-sub-" + clientName).unstarted(this::subscribeLoop);
    }

    /** Connects and fails fast if Redis can't be reached. */
    public static RedisBus connect(RedisSettings settings, String clientName, Logger logger) {
        DefaultJedisClientConfig.Builder config = DefaultJedisClientConfig.builder()
                .clientName("rapid-" + clientName)
                .connectionTimeoutMillis(5_000)
                .socketTimeoutMillis(5_000);
        if (settings.password() != null && !settings.password().isBlank()) {
            config.password(settings.password());
        }
        GenericObjectPoolConfig<Connection> pool = new GenericObjectPoolConfig<>();
        pool.setMaxTotal(16);
        pool.setMaxIdle(8);
        pool.setMinIdle(1);
        pool.setTestWhileIdle(true);
        RedisClient client = RedisClient.builder()
                .hostAndPort(settings.host(), settings.port())
                .clientConfig(config.build())
                .poolConfig(pool)
                .build();
        logger.info("Connecting to {}", settings);
        try {
            client.ping();
        } catch (RuntimeException e) {
            client.close();
            throw e;
        }
        RedisBus bus = new RedisBus(client, logger, clientName);
        bus.subscriber.start();
        return bus;
    }

    // ------------------------------------------------------------------ pub/sub

    /** Registers {@code handler} for {@code channel} (with or without the {@code rapid:} prefix). */
    public void subscribe(String channel, Consumer<String> handler) {
        handlers.computeIfAbsent(prefixed(channel), k -> new CopyOnWriteArrayList<>()).add(handler);
    }

    /** Like {@link #subscribe} for JSON payloads published with {@link #publishJson}. */
    public <T> void subscribeJson(String channel, Class<T> type, Consumer<T> handler) {
        subscribe(channel, raw -> {
            T message;
            try {
                message = GSON.fromJson(raw, type);
            } catch (RuntimeException e) {
                logger.warn("Dropping malformed message on {}: {}", channel, e.toString());
                return;
            }
            handler.accept(message);
        });
    }

    public void publish(String channel, String message) {
        client.publish(prefixed(channel), message);
    }

    public void publishJson(String channel, Object message) {
        publish(channel, GSON.toJson(message));
    }

    private void subscribeLoop() {
        long backoff = 1_000;
        while (!closed) {
            JedisPubSub ps = new JedisPubSub() {
                @Override
                public void onPMessage(String pattern, String channel, String message) {
                    dispatch(channel, message);
                }

                @Override
                public void onPSubscribe(String pattern, int subscribedChannels) {
                    logger.info("Subscribed to Redis channels {}", pattern);
                }
            };
            pubSub = ps;
            try {
                client.psubscribe(ps, PREFIX + "*");
                backoff = 1_000; // returned normally: unsubscribed by close()
            } catch (RuntimeException e) {
                if (closed) {
                    return;
                }
                logger.warn("Redis subscription lost ({}); retrying in {}s", e.getMessage(), backoff / 1000);
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    return;
                }
                backoff = Math.min(backoff * 2, 30_000);
            }
        }
    }

    private void dispatch(String channel, String message) {
        List<Consumer<String>> list = handlers.get(channel);
        if (list == null) {
            return;
        }
        for (Consumer<String> h : list) {
            try {
                h.accept(message);
            } catch (RuntimeException e) {
                logger.error("Handler for {} failed", channel, e);
            }
        }
    }

    // ------------------------------------------------------------------ key/value

    /**
     * Writes {@code fields} into the hash at {@code key} and (re)sets it to expire after
     * {@code ttlSeconds}. Fields not in {@code fields} are left as they were.
     */
    public void putHash(String key, Map<String, String> fields, long ttlSeconds) {
        String k = prefixed(key);
        try (AbstractPipeline p = client.pipelined()) {
            p.hset(k, fields);
            p.expire(k, ttlSeconds);
            p.sync();
        }
    }

    public Map<String, String> getHash(String key) {
        return client.hgetAll(prefixed(key));
    }

    /** Reads many hashes in one round trip. Missing keys map to an empty map. */
    public Map<String, Map<String, String>> getHashes(Collection<String> keys) {
        Map<String, Response<Map<String, String>>> pending = new LinkedHashMap<>();
        try (AbstractPipeline p = client.pipelined()) {
            for (String key : keys) {
                pending.put(key, p.hgetAll(prefixed(key)));
            }
            p.sync();
        }
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        pending.forEach((k, r) -> out.put(k, r.get()));
        return out;
    }

    public void addToSet(String key, String... members) {
        client.sadd(prefixed(key), members);
    }

    public void removeFromSet(String key, String... members) {
        client.srem(prefixed(key), members);
    }

    /** Adds {@code delta} to the counter at {@code key}, (re)sets its expiry, and returns the new value. */
    public long incrementBy(String key, long delta, long ttlSeconds) {
        String k = prefixed(key);
        try (AbstractPipeline p = client.pipelined()) {
            Response<Long> value = p.incrBy(k, delta);
            p.expire(k, ttlSeconds);
            p.sync();
            return value.get();
        }
    }

    public boolean isMember(String key, String member) {
        return client.sismember(prefixed(key), member);
    }

    public Set<String> members(String key) {
        return client.smembers(prefixed(key));
    }

    public String get(String key) {
        return client.get(prefixed(key));
    }

    public void set(String key, String value) {
        client.set(prefixed(key), value);
    }

    public void delete(String key) {
        client.del(prefixed(key));
    }

    private static String prefixed(String key) {
        return key.startsWith(PREFIX) ? key : PREFIX + key;
    }

    @Override
    public void close() {
        closed = true;
        JedisPubSub ps = pubSub;
        if (ps != null && ps.isSubscribed()) {
            try {
                ps.punsubscribe();
            } catch (RuntimeException ignored) {
                // connection already gone
            }
        }
        subscriber.interrupt();
        client.close();
    }
}
