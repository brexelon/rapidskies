package us.rapidskies.common.testing;

import java.sql.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mariadb.MariaDBContainer;
import us.rapidskies.common.config.DatabaseSettings;
import us.rapidskies.common.config.RedisSettings;
import us.rapidskies.common.db.Database;

/**
 * Real MariaDB and Redis for integration tests, started once per test JVM. Test classes
 * that use them are annotated {@code @EnabledIf("us.rapidskies.common.testing.TestServices#dockerAvailable")},
 * so they're skipped on machines without Docker and always run in CI.
 */
public final class TestServices {

    public static final Logger LOG = LoggerFactory.getLogger("rapid-test");
    public static final String REDIS_PASSWORD = "test-secret";

    private static MariaDBContainer mariadb;
    private static GenericContainer<?> redis;
    private static Database database;

    private TestServices() {
    }

    public static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** A pool on a migrated {@code core} schema. Shared: tests clear the tables they use. */
    public static synchronized Database database() throws Exception {
        if (database == null) {
            mariadb = new MariaDBContainer("mariadb:11.4");
            mariadb.start();
            database = Database.connect(databaseSettings(), "rapid-test", LOG);
            database.migrate(TestServices.class.getClassLoader(), "core");
        }
        return database;
    }

    public static DatabaseSettings databaseSettings() {
        return new DatabaseSettings(mariadb.getHost(), mariadb.getMappedPort(3306), mariadb.getDatabaseName(),
                mariadb.getUsername(), mariadb.getPassword(), 8);
    }

    public static void truncate(String... tables) throws Exception {
        database().withConnection(c -> {
            try (Statement st = c.createStatement()) {
                for (String t : tables) {
                    st.execute("TRUNCATE TABLE " + t);
                }
            }
            return null;
        });
    }

    public static synchronized RedisSettings redis() {
        if (redis == null) {
            redis = new GenericContainer<>("redis:7.4-alpine")
                    .withExposedPorts(6379)
                    .withCommand("redis-server", "--requirepass", REDIS_PASSWORD);
            redis.start();
        }
        return new RedisSettings(redis.getHost(), redis.getMappedPort(6379), REDIS_PASSWORD);
    }
}
