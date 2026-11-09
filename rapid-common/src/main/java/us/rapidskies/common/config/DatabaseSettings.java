package us.rapidskies.common.config;

/** Connection settings for the shared MariaDB schema that every rapid-* plugin uses. */
public record DatabaseSettings(String host, int port, String database, String user, String password, int poolSize) {

    public DatabaseSettings {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("database.host is empty");
        }
        if (database == null || database.isBlank()) {
            throw new IllegalArgumentException("database.name is empty");
        }
        if (poolSize < 1) {
            throw new IllegalArgumentException("database.pool-size must be at least 1");
        }
    }

    public static DatabaseSettings from(ConfigView c) {
        return new DatabaseSettings(
                c.string("host", "127.0.0.1"),
                c.integer("port", 3306),
                c.string("name", "rapidskies"),
                c.string("user", "rapidskies"),
                c.string("password", ""),
                c.integer("pool-size", 6));
    }

    public String jdbcUrl() {
        return "jdbc:mariadb://" + host + ":" + port + "/" + database;
    }

    @Override
    public String toString() {
        // Never print the password (configs get pasted into support channels).
        return "DatabaseSettings[" + user + "@" + host + ":" + port + "/" + database + ", pool=" + poolSize + "]";
    }
}
