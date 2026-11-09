package us.rapidskies.common.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.slf4j.Logger;
import us.rapidskies.common.config.DatabaseSettings;

/**
 * Connection pool for the shared {@code rapidskies} MariaDB schema.
 *
 * <p>Blocking calls ({@link #withConnection}, {@link #transaction}) must never run on a
 * server's main thread; use {@link #async} or call them from an async event. The async
 * executor uses virtual threads, and the pool size caps how many queries run at once.
 *
 * <p>Sessions run in UTC, so {@code CURRENT_TIMESTAMP} defaults and every DATETIME
 * column hold UTC wall-clock time.
 */
public final class Database implements AutoCloseable {

    private final HikariDataSource pool;
    private final ExecutorService executor;
    private final Logger logger;

    private Database(HikariDataSource pool, Logger logger) {
        this.pool = pool;
        this.logger = logger;
        this.executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("rapid-db-", 0).factory());
    }

    /** Opens the pool and fails fast if the database can't be reached. */
    public static Database connect(DatabaseSettings settings, String poolName, Logger logger) {
        HikariConfig config = new HikariConfig();
        config.setPoolName(poolName);
        // A class reference rather than a string, so it survives relocation in the shaded jar.
        config.setDriverClassName(org.mariadb.jdbc.Driver.class.getName());
        config.setJdbcUrl(settings.jdbcUrl());
        config.setUsername(settings.user());
        config.setPassword(settings.password());
        config.setMaximumPoolSize(settings.poolSize());
        config.setMinimumIdle(Math.min(2, settings.poolSize()));
        config.setConnectionTimeout(TimeUnit.SECONDS.toMillis(10));
        // Shorter than MariaDB's default wait_timeout (8h) and any NAT idle cutoff.
        config.setMaxLifetime(TimeUnit.MINUTES.toMillis(25));
        config.setKeepaliveTime(TimeUnit.MINUTES.toMillis(5));
        config.setConnectionInitSql("SET time_zone = '+00:00'");
        config.addDataSourceProperty("characterEncoding", "utf8mb4");
        logger.info("Connecting to {}", settings);
        return new Database(new HikariDataSource(config), logger);
    }

    public DataSource dataSource() {
        return pool;
    }

    public <T> T withConnection(SqlFunction<Connection, T> work) throws SQLException {
        try (Connection c = pool.getConnection()) {
            return work.apply(c);
        }
    }

    /**
     * Runs {@code work} in one transaction: commits if it returns, rolls back if it throws.
     * Use this for anything that writes to more than one row (ledger + balance, etc.).
     */
    public <T> T transaction(SqlFunction<Connection, T> work) throws SQLException {
        try (Connection c = pool.getConnection()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                T result = work.apply(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                try {
                    c.rollback();
                } catch (SQLException rollback) {
                    e.addSuppressed(rollback);
                }
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
        }
    }

    /** Runs blocking database work off the calling thread. */
    public <T> CompletableFuture<T> async(SqlSupplier<T> work) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return work.get();
            } catch (SQLException e) {
                throw new DatabaseException(e);
            }
        }, executor);
    }

    /** Applies this module's pending migrations (see {@link Migrator}). */
    public void migrate(ClassLoader loader, String module) throws SQLException {
        new Migrator(this, logger).migrate(loader, module);
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                logger.warn("Database work still running after 10s at shutdown");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        pool.close();
    }

    @FunctionalInterface
    public interface SqlFunction<I, O> {
        O apply(I input) throws SQLException;
    }

    @FunctionalInterface
    public interface SqlSupplier<T> {
        T get() throws SQLException;
    }
}
