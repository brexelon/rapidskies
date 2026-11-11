package us.rapidskies.common.quest;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import us.rapidskies.common.db.Database;

/**
 * {@code quests_progress}, {@code player_stats} and {@code achievements} (PLAN.md §7, §10).
 *
 * <p>Progress is only ever added ({@code progress = LEAST(target, progress + n)}), never
 * set, so the server a player just left and the one they joined can both write without
 * losing or double counting anything. A quest completes exactly once: the conditional
 * update that stamps {@code completed_at} must change one row, and only that caller pays.
 * Every method blocks.
 */
public final class QuestRepository {

    public record Assigned(int slot, String quest, long target) {
    }

    public record Row(String period, int slot, String kind, String quest, long progress, long target,
                      Instant completedAt) {
        public boolean completed() {
            return completedAt != null;
        }
    }

    /** Progress to add to one assigned quest. */
    public record Step(String period, int slot, long delta) {
    }

    /** A quest after a flush; {@code justCompleted} only for the call that completed it. */
    public record Advanced(String period, int slot, long progress, boolean justCompleted) {
    }

    /** What a flush did: quests advanced and the new lifetime totals of the stats it added to. */
    public record Flushed(List<Advanced> quests, Map<String, Long> stats) {
    }

    private final Database db;

    public QuestRepository(Database db) {
        this.db = db;
    }

    /** Assigns quests to a period's slots; slots already filled keep their quest. */
    public void assign(UUID player, String period, String kind, List<Assigned> quests) throws SQLException {
        if (quests.isEmpty()) {
            return;
        }
        db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT IGNORE INTO quests_progress (uuid, period, slot,"
                    + " kind, quest, target) VALUES (?, ?, ?, ?, ?, ?)")) {
                for (Assigned a : quests) {
                    ps.setString(1, player.toString());
                    ps.setString(2, period);
                    ps.setInt(3, a.slot());
                    ps.setString(4, kind);
                    ps.setString(5, a.quest());
                    ps.setLong(6, Math.max(1, a.target()));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    /** The player's quests in {@code periods}, by period then slot. */
    public List<Row> quests(UUID player, Collection<String> periods) throws SQLException {
        if (periods.isEmpty()) {
            return List.of();
        }
        String marks = String.join(", ", java.util.Collections.nCopies(periods.size(), "?"));
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT period, slot, kind, quest, progress, target,"
                    + " completed_at FROM quests_progress WHERE uuid = ? AND period IN (" + marks + ")"
                    + " ORDER BY period, slot")) {
                int i = 1;
                ps.setString(i++, player.toString());
                for (String p : periods) {
                    ps.setString(i++, p);
                }
                List<Row> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Row(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getLong(5),
                                rs.getLong(6), instant(rs.getObject(7, LocalDateTime.class))));
                    }
                }
                return out;
            }
        });
    }

    /** Adds quest progress and lifetime stats in one transaction. */
    public Flushed flush(UUID player, List<Step> steps, Map<String, Long> stats) throws SQLException {
        for (int attempt = 1; ; attempt++) {
            try {
                return db.transaction(c -> flushIn(c, player, steps, stats));
            } catch (SQLException e) {
                if (attempt >= 3 || !"40001".equals(e.getSQLState())) {
                    throw e;
                }
            }
        }
    }

    private static Flushed flushIn(Connection c, UUID player, List<Step> steps, Map<String, Long> stats)
            throws SQLException {
        List<Advanced> advanced = new ArrayList<>();
        for (Step s : steps) {
            if (s.delta() <= 0) {
                continue;
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE quests_progress SET progress = LEAST(target,"
                    + " progress + ?) WHERE uuid = ? AND period = ? AND slot = ? AND completed_at IS NULL")) {
                ps.setLong(1, s.delta());
                ps.setString(2, player.toString());
                ps.setString(3, s.period());
                ps.setInt(4, s.slot());
                if (ps.executeUpdate() == 0) {
                    continue; // done already (another server finished it) or gone
                }
            }
            boolean completed;
            try (PreparedStatement ps = c.prepareStatement("UPDATE quests_progress SET completed_at ="
                    + " CURRENT_TIMESTAMP(3) WHERE uuid = ? AND period = ? AND slot = ? AND completed_at IS NULL"
                    + " AND progress >= target")) {
                ps.setString(1, player.toString());
                ps.setString(2, s.period());
                ps.setInt(3, s.slot());
                completed = ps.executeUpdate() == 1;
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT progress FROM quests_progress WHERE uuid = ? AND period = ? AND slot = ?")) {
                ps.setString(1, player.toString());
                ps.setString(2, s.period());
                ps.setInt(3, s.slot());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    advanced.add(new Advanced(s.period(), s.slot(), rs.getLong(1), completed));
                }
            }
        }
        Map<String, Long> totals = new HashMap<>();
        for (Map.Entry<String, Long> e : stats.entrySet()) {
            if (e.getValue() <= 0) {
                continue;
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO player_stats (uuid, stat, value) VALUES"
                    + " (?, ?, ?) ON DUPLICATE KEY UPDATE value = value + VALUES(value)")) {
                ps.setString(1, player.toString());
                ps.setString(2, e.getKey());
                ps.setLong(3, e.getValue());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT value FROM player_stats WHERE uuid = ? AND stat = ?")) {
                ps.setString(1, player.toString());
                ps.setString(2, e.getKey());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    totals.put(e.getKey(), rs.getLong(1));
                }
            }
        }
        return new Flushed(advanced, totals);
    }

    public Map<String, Long> stats(UUID player) throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT stat, value FROM player_stats WHERE uuid = ?")) {
                ps.setString(1, player.toString());
                Map<String, Long> out = new HashMap<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.put(rs.getString(1), rs.getLong(2));
                    }
                }
                return out;
            }
        });
    }

    /** True for the one call that completes it. */
    public boolean completeAchievement(UUID player, String achievement) throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT IGNORE INTO achievements (uuid, achievement) VALUES (?, ?)")) {
                ps.setString(1, player.toString());
                ps.setString(2, achievement);
                return ps.executeUpdate() == 1;
            }
        });
    }

    public Map<String, Instant> achievements(UUID player) throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT achievement, completed_at FROM achievements WHERE uuid = ?")) {
                ps.setString(1, player.toString());
                Map<String, Instant> out = new HashMap<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.put(rs.getString(1), instant(rs.getObject(2, LocalDateTime.class)));
                    }
                }
                return out;
            }
        });
    }

    private static Instant instant(LocalDateTime t) {
        return t == null ? null : t.toInstant(ZoneOffset.UTC);
    }
}
