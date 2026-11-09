package us.rapidskies.common.profile;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import us.rapidskies.common.db.Database;
import us.rapidskies.common.rank.PlusColor;

/** Reads and writes {@code players}. Every method blocks: call from async contexts only. */
public final class ProfileRepository {

    private static final String COLUMNS =
            "uuid, name, first_join, last_join, last_server, network_xp, locale, plus_color, tailwind_banked";

    private final Database db;

    public ProfileRepository(Database db) {
        this.db = db;
    }

    /**
     * Records a login: creates the row on first join, otherwise updates the name (it may
     * have changed), last join time and server. Returns the profile as stored.
     */
    public PlayerProfile recordLogin(UUID uuid, String name, String server) throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO players (uuid, name, last_server) VALUES (?, ?, ?)"
                            + " ON DUPLICATE KEY UPDATE name = VALUES(name), last_server = VALUES(last_server),"
                            + " last_join = CURRENT_TIMESTAMP(3)")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, name);
                ps.setString(3, server);
                ps.executeUpdate();
            }
            return find(c, uuid).orElseThrow(() -> new SQLException("players row vanished for " + uuid));
        });
    }

    public Optional<PlayerProfile> find(UUID uuid) throws SQLException {
        return db.withConnection(c -> find(c, uuid));
    }

    /** The most recent player to use {@code name} (names are reused after changes). */
    public Optional<PlayerProfile> findByName(String name) throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT " + COLUMNS + " FROM players WHERE name = ? ORDER BY last_join DESC LIMIT 1")) {
                ps.setString(1, name);
                return one(ps);
            }
        });
    }

    public void setPlusColor(UUID uuid, PlusColor color) throws SQLException {
        db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE players SET plus_color = ? WHERE uuid = ?")) {
                ps.setString(1, color == null ? null : color.id());
                ps.setString(2, uuid.toString());
                return ps.executeUpdate();
            }
        });
    }

    private static Optional<PlayerProfile> find(Connection c, UUID uuid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM players WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            return one(ps);
        }
    }

    private static Optional<PlayerProfile> one(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                return Optional.empty();
            }
            return Optional.of(new PlayerProfile(
                    UUID.fromString(rs.getString("uuid")),
                    rs.getString("name"),
                    instant(rs, "first_join"),
                    instant(rs, "last_join"),
                    rs.getString("last_server"),
                    rs.getLong("network_xp"),
                    rs.getString("locale"),
                    PlusColor.byId(rs.getString("plus_color")).orElse(null),
                    rs.getLong("tailwind_banked")));
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        LocalDateTime t = rs.getObject(column, LocalDateTime.class);
        return t == null ? null : t.toInstant(ZoneOffset.UTC);
    }
}
