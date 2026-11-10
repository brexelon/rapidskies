package us.rapidskies.horizons.data;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import us.rapidskies.common.db.Database;
import us.rapidskies.horizons.frontier.FrontierRow;
import us.rapidskies.horizons.frontier.Modifier;

/**
 * {@code frontier_history} and the {@code logout_frontier} column (migration module
 * {@code horizons}, V2). Every method blocks: call it off the main thread.
 */
public final class FrontierRepository {

    private static final String COLUMNS = "id, modifier, seed, chapter, state, created_at, opened_at, closes_at, "
            + "closed_at, end_opens_at, dragon_killed_at, archive_path";

    private final Database db;

    public FrontierRepository(Database db) {
        this.db = db;
    }

    /** Frontiers that aren't closed (the open one and one being prepared), oldest first. */
    public List<FrontierRow> live() throws SQLException {
        return query("SELECT " + COLUMNS + " FROM frontier_history WHERE state <> 'CLOSED' ORDER BY id");
    }

    /** Closed Frontiers whose worlds are still archived. */
    public List<FrontierRow> archived() throws SQLException {
        return query("SELECT " + COLUMNS + " FROM frontier_history WHERE state = 'CLOSED' AND archive_path IS NOT NULL"
                + " ORDER BY id");
    }

    /** The most recent Frontier of any state (for the next modifier in the cycle). */
    public Optional<FrontierRow> latest() throws SQLException {
        List<FrontierRow> rows = query("SELECT " + COLUMNS + " FROM frontier_history ORDER BY id DESC LIMIT 1");
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    /** How many Frontiers there have been (the next one's number, minus one). */
    public int count() throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM frontier_history");
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
    }

    /** Frontier {@code id}'s number (1-based): how many Frontiers there were up to and including it. */
    public int number(int id) throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM frontier_history WHERE id <= ?")) {
                ps.setInt(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    /** Gives Frontier {@code id} its Chapter; false (changing nothing) if it already has one. */
    public boolean setChapterIfMissing(int id, String chapter) throws SQLException {
        return update("UPDATE frontier_history SET chapter = ? WHERE id = ? AND chapter IS NULL", chapter, id) == 1;
    }

    /** Inserts a PREPARING Frontier and returns it with its id. */
    public FrontierRow create(Modifier modifier, long seed, Optional<String> chapter, Instant now) throws SQLException {
        int id = db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO frontier_history (modifier, seed, chapter, state,"
                    + " created_at) VALUES (?, ?, ?, 'PREPARING', ?)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, modifier.id());
                ps.setLong(2, seed);
                ps.setString(3, chapter.orElse(null));
                ps.setObject(4, utc(now));
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    return keys.getInt(1);
                }
            }
        });
        return new FrontierRow(id, modifier, seed, chapter, FrontierRow.State.PREPARING, now, Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    /**
     * Closes {@code closing} (if any) and opens {@code opening}, in one transaction, so there's
     * never no open Frontier or two of them.
     */
    public void rotate(OptionalInt closing, int opening, Instant now, Instant closesAt, Instant endOpensAt)
            throws SQLException {
        db.transaction(c -> {
            if (closing.isPresent()) {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE frontier_history SET state = 'CLOSED', closed_at = ? WHERE id = ?")) {
                    ps.setObject(1, utc(now));
                    ps.setInt(2, closing.getAsInt());
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE frontier_history SET state = 'OPEN', opened_at = ?,"
                    + " closes_at = ?, end_opens_at = ? WHERE id = ?")) {
                ps.setObject(1, utc(now));
                ps.setObject(2, utc(closesAt));
                ps.setObject(3, endOpensAt == null ? null : utc(endOpensAt));
                ps.setInt(4, opening);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public void setClosesAt(int id, Instant closesAt) throws SQLException {
        update("UPDATE frontier_history SET closes_at = ? WHERE id = ?", utc(closesAt), id);
    }

    public void setEndOpensAt(int id, Instant at) throws SQLException {
        update("UPDATE frontier_history SET end_opens_at = ? WHERE id = ?", utc(at), id);
    }

    /** Records the dragon kill; false if it was already recorded (the rewards were paid then). */
    public boolean markDragonKilled(int id, Instant at) throws SQLException {
        return update("UPDATE frontier_history SET dragon_killed_at = ? WHERE id = ? AND dragon_killed_at IS NULL",
                utc(at), id) == 1;
    }

    public void setArchivePath(int id, String path) throws SQLException {
        update("UPDATE frontier_history SET archive_path = ? WHERE id = ?", path, id);
    }

    /** Remembers which Frontier {@code player} logged out in (null: none). */
    public void setLogoutFrontier(UUID player, Integer frontier) throws SQLException {
        db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO horizons_players (uuid, logout_frontier)"
                    + " VALUES (?, ?) ON DUPLICATE KEY UPDATE logout_frontier = VALUES(logout_frontier)")) {
                ps.setString(1, player.toString());
                ps.setObject(2, frontier);
                return ps.executeUpdate();
            }
        });
    }

    public OptionalInt logoutFrontier(UUID player) throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT logout_frontier FROM horizons_players WHERE uuid = ?")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return OptionalInt.empty();
                    }
                    int v = rs.getInt(1);
                    return rs.wasNull() ? OptionalInt.empty() : OptionalInt.of(v);
                }
            }
        });
    }

    private int update(String sql, Object... args) throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                return ps.executeUpdate();
            }
        });
    }

    private List<FrontierRow> query(String sql) throws SQLException {
        return db.withConnection(c -> {
            List<FrontierRow> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Optional<Modifier> modifier = Modifier.parse(rs.getString(2));
                    if (modifier.isEmpty()) {
                        continue; // a modifier this version doesn't have; leave it alone
                    }
                    out.add(new FrontierRow(rs.getInt(1), modifier.get(), rs.getLong(3),
                            Optional.ofNullable(rs.getString(4)), FrontierRow.State.parse(rs.getString(5)),
                            instant(rs, 6).orElseThrow(), instant(rs, 7), instant(rs, 8), instant(rs, 9),
                            instant(rs, 10), instant(rs, 11), Optional.ofNullable(rs.getString(12))));
                }
            }
            return out;
        });
    }

    private static LocalDateTime utc(Instant at) {
        return LocalDateTime.ofInstant(at, ZoneOffset.UTC);
    }

    private static Optional<Instant> instant(ResultSet rs, int column) throws SQLException {
        LocalDateTime t = rs.getObject(column, LocalDateTime.class);
        return t == null ? Optional.empty() : Optional.of(t.toInstant(ZoneOffset.UTC));
    }
}
