package us.rapidskies.common.registry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import us.rapidskies.common.redis.RedisBus;

/**
 * Heartbeats in Redis: {@code rapid:server:<id>} holds each server's {@link ServerStatus}
 * and expires on its own if the server dies; {@code rapid:servers} indexes the ids.
 */
public final class ServerRegistry {

    /** Backends publish this often... */
    public static final long HEARTBEAT_SECONDS = 3;
    /** ...and a heartbeat older than this counts as down. */
    public static final long STALE_MILLIS = 10_000;
    private static final long TTL_SECONDS = 15;
    private static final String INDEX = "servers";

    private final RedisBus redis;

    public ServerRegistry(RedisBus redis) {
        this.redis = redis;
    }

    public void publish(ServerStatus status) {
        redis.putHash(key(status.id()), status.toHash(), TTL_SECONDS);
        redis.addToSet(INDEX, status.id());
    }

    /** Clean shutdown: drop the heartbeat now instead of waiting for it to expire. */
    public void remove(String id) {
        redis.delete(key(id));
        redis.removeFromSet(INDEX, id);
    }

    /** Every server with a live heartbeat. Ids whose heartbeat expired are pruned from the index. */
    public List<ServerStatus> fetchAll() {
        Set<String> ids = redis.members(INDEX);
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<String, Map<String, String>> hashes = redis.getHashes(ids.stream().map(ServerRegistry::key).toList());
        List<ServerStatus> out = new ArrayList<>(ids.size());
        for (String id : ids) {
            ServerStatus s = ServerStatus.fromHash(id, hashes.get(key(id)));
            if (s == null) {
                redis.removeFromSet(INDEX, id);
            } else {
                out.add(s);
            }
        }
        return out;
    }

    private static String key(String id) {
        return "server:" + id;
    }
}
