package us.rapidskies.common.config;

/** Connection settings for the shared Redis (pub/sub, server registry, presence). */
public record RedisSettings(String host, int port, String password) {

    public RedisSettings {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("redis.host is empty");
        }
    }

    public static RedisSettings from(ConfigView c) {
        return new RedisSettings(c.string("host", "127.0.0.1"), c.integer("port", 6379), c.string("password", ""));
    }

    @Override
    public String toString() {
        return "RedisSettings[" + host + ":" + port + "]";
    }
}
