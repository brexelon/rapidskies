package us.rapidskies.common.db;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;

/**
 * Versioned SQL migrations, one history per module.
 *
 * <p>Each module keeps its scripts in {@code db/migration/<module>/} with an {@code index}
 * file listing them in order (jar resources can't be listed reliably). Scripts are named
 * {@code V<version>__<description>.sql}, run once, and are never edited after release:
 * the checksum of every applied script is verified on each start, so a changed script
 * stops the server instead of silently diverging from production.
 *
 * <p>Several servers may start at once, so the whole run holds a MariaDB named lock.
 * MariaDB commits DDL implicitly, so a script that fails halfway stays half applied: keep
 * each script small and fix forward with a new version.
 *
 * <p>This replaces Flyway from the original plan: one small class, no classloader or
 * relocation quirks inside plugin jars, and nothing to keep in step with MariaDB releases.
 */
final class Migrator {

    static final String HISTORY_TABLE = "rapid_schema_history";
    private static final Pattern NAME = Pattern.compile("V(\\d+)__([A-Za-z0-9_]+)\\.sql");
    private static final Pattern STATEMENT_END = Pattern.compile(";\\s*(?:\\n|$)");
    private static final int LOCK_TIMEOUT_SECONDS = 120;

    private final Database db;
    private final Logger logger;

    Migrator(Database db, Logger logger) {
        this.db = db;
        this.logger = logger;
    }

    record Migration(int version, String description, String script, String checksum) {
    }

    void migrate(ClassLoader loader, String module) throws SQLException {
        List<Migration> migrations = discover(loader, module);
        db.withConnection(c -> {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS " + HISTORY_TABLE + " ("
                        + " module VARCHAR(32) NOT NULL,"
                        + " version INT NOT NULL,"
                        + " description VARCHAR(200) NOT NULL,"
                        + " checksum CHAR(64) NOT NULL,"
                        + " applied_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),"
                        + " PRIMARY KEY (module, version)"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
            }
            String lock = "rapid_migrate_" + module;
            acquire(c, lock);
            try {
                apply(c, module, migrations);
            } finally {
                try (PreparedStatement ps = c.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                    ps.setString(1, lock);
                    ps.execute();
                }
            }
            return null;
        });
    }

    private void apply(Connection c, String module, List<Migration> migrations) throws SQLException {
        Map<Integer, String> applied = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT version, checksum FROM " + HISTORY_TABLE + " WHERE module = ?")) {
            ps.setString(1, module);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    applied.put(rs.getInt(1), rs.getString(2));
                }
            }
        }
        int ran = 0;
        for (Migration m : migrations) {
            String existing = applied.remove(m.version());
            if (existing != null) {
                if (!existing.equals(m.checksum())) {
                    throw new IllegalStateException("Migration " + module + " V" + m.version()
                            + " was changed after it was applied. Revert the script and add a new version instead.");
                }
                continue;
            }
            logger.info("Applying migration {} V{} ({})", module, m.version(), m.description());
            try (Statement st = c.createStatement()) {
                for (String sql : statements(m.script())) {
                    st.execute(sql);
                }
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + HISTORY_TABLE
                    + " (module, version, description, checksum) VALUES (?, ?, ?, ?)")) {
                ps.setString(1, module);
                ps.setInt(2, m.version());
                ps.setString(3, m.description());
                ps.setString(4, m.checksum());
                ps.executeUpdate();
            }
            ran++;
        }
        if (!applied.isEmpty()) {
            logger.warn("Database has {} migration(s) {} that this jar doesn't know: an older plugin build is "
                    + "running against a newer schema.", module, applied.keySet());
        }
        if (ran > 0) {
            logger.info("{} schema is up to date ({} migration(s) applied)", module, ran);
        }
    }

    private static void acquire(Connection c, String lock) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT GET_LOCK(?, ?)")) {
            ps.setString(1, lock);
            ps.setInt(2, LOCK_TIMEOUT_SECONDS);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || rs.getInt(1) != 1) {
                    throw new SQLException("Timed out waiting for the migration lock " + lock
                            + " (another server is migrating, or a previous run is stuck)");
                }
            }
        }
    }

    static List<Migration> discover(ClassLoader loader, String module) {
        String dir = "db/migration/" + module + "/";
        String index = read(loader, dir + "index");
        if (index == null) {
            throw new IllegalStateException("No migrations index at " + dir + "index");
        }
        List<Migration> out = new ArrayList<>();
        for (String line : index.split("\n")) {
            String name = line.strip();
            if (name.isEmpty() || name.startsWith("#")) {
                continue;
            }
            Matcher m = NAME.matcher(name);
            if (!m.matches()) {
                throw new IllegalStateException("Bad migration name '" + name + "' in " + dir + "index");
            }
            String script = read(loader, dir + name);
            if (script == null) {
                throw new IllegalStateException("Migration " + dir + name + " is listed in the index but missing");
            }
            out.add(new Migration(Integer.parseInt(m.group(1)), m.group(2).replace('_', ' '), script, sha256(script)));
        }
        out.sort(Comparator.comparingInt(Migration::version));
        for (int i = 1; i < out.size(); i++) {
            if (out.get(i).version() == out.get(i - 1).version()) {
                throw new IllegalStateException("Duplicate migration version V" + out.get(i).version() + " in " + dir);
            }
        }
        return out;
    }

    /** Splits a script into statements: drops {@code --} comment lines, splits on a trailing {@code ;}. */
    static List<String> statements(String script) {
        StringBuilder body = new StringBuilder();
        for (String line : script.split("\n")) {
            if (!line.strip().startsWith("--")) {
                body.append(line).append('\n');
            }
        }
        List<String> out = new ArrayList<>();
        for (String part : STATEMENT_END.split(body)) {
            if (!part.isBlank()) {
                out.add(part.strip());
            }
        }
        return out;
    }

    private static String read(ClassLoader loader, String resource) {
        try (InputStream in = loader.getResourceAsStream(resource)) {
            // Normalized so a Windows checkout (CRLF) produces the same checksum as CI.
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new IllegalStateException("Can't read " + resource, e);
        }
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
