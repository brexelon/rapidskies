package us.rapidskies.common.parkour;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import us.rapidskies.common.db.Database;

/**
 * Best parkour times (table {@code parkour_times}; PLAN.md §7): each player's best per
 * course, all time and per ISO week (UTC, Monday to Sunday). Faster is better; on equal
 * times, whoever set theirs first ranks higher. Every method blocks: call it off the main
 * thread.
 */
public final class ParkourTimes {

    /** The {@code period} of all-time bests. */
    public static final String ALL_TIME = "all";

    private final Database db;

    public ParkourTimes(Database db) {
        this.db = db;
    }

    /** One line of a leaderboard. {@code name} is the last name the player joined with. */
    public record Entry(int rank, UUID uuid, String name, int timeMs) {
    }

    /** A player's best in one period and where it ranks. */
    public record Standing(int timeMs, int rank) {
    }

    /**
     * What a finished run did.
     *
     * @param previousBest the player's all-time best before this run, if they had one
     * @param allTime      their all-time best and rank after this run
     * @param weekly       their best this week and rank after this run
     */
    public record Result(Optional<Integer> previousBest, Standing allTime, boolean weeklyBest, Standing weekly) {

        /** First finish, or faster than every earlier one. */
        public boolean personalBest() {
            return previousBest.map(prev -> allTime.timeMs() < prev).orElse(true);
        }
    }

    /** The period key of the ISO week {@code at} falls in, e.g. {@code 2026-W45}. */
    public static String week(Instant at) {
        ZonedDateTime t = at.atZone(ZoneOffset.UTC);
        return String.format("%d-W%02d", t.get(IsoFields.WEEK_BASED_YEAR), t.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }

    /** When the week {@code at} falls in ends: the next Monday, 00:00 UTC. */
    public static Instant weekEnd(Instant at) {
        return at.atZone(ZoneOffset.UTC).truncatedTo(ChronoUnit.DAYS)
                .with(TemporalAdjusters.next(DayOfWeek.MONDAY)).toInstant();
    }

    /** Records a finished run, keeping only the player's best in each period. */
    public Result record(String course, UUID player, int timeMs, Instant at) throws SQLException {
        if (timeMs <= 0) {
            throw new IllegalArgumentException("a run takes some time, not " + timeMs + " ms");
        }
        String week = week(at);
        return db.transaction(c -> {
            Optional<Integer> previous = best(c, course, ALL_TIME, player);
            keepBest(c, course, ALL_TIME, player, timeMs, at, previous);
            Optional<Integer> previousWeek = best(c, course, week, player);
            keepBest(c, course, week, player, timeMs, at, previousWeek);
            boolean weeklyBest = previousWeek.map(prev -> timeMs < prev).orElse(true);
            return new Result(previous, standing(c, course, ALL_TIME, player).orElseThrow(), weeklyBest,
                    standing(c, course, week, player).orElseThrow());
        });
    }

    /** The fastest players in a period, best first. */
    public List<Entry> top(String course, String period, int limit) throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT t.uuid, p.name, t.time_ms FROM parkour_times t LEFT JOIN players p ON p.uuid = t.uuid"
                            + " WHERE t.course = ? AND t.period = ? ORDER BY t.time_ms, t.achieved_at, t.uuid LIMIT ?")) {
                ps.setString(1, course);
                ps.setString(2, period);
                ps.setInt(3, limit);
                List<Entry> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String name = rs.getString(2);
                        out.add(new Entry(out.size() + 1, UUID.fromString(rs.getString(1)), name == null ? "?" : name,
                                rs.getInt(3)));
                    }
                }
                return out;
            }
        });
    }

    /** A player's best in a period and its rank, if they've finished the course in it. */
    public Optional<Standing> standing(String course, String period, UUID player) throws SQLException {
        return db.withConnection(c -> standing(c, course, period, player));
    }

    private static Optional<Integer> best(Connection c, String course, String period, UUID player) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT time_ms FROM parkour_times WHERE course = ? AND period = ? AND uuid = ? FOR UPDATE")) {
            ps.setString(1, course);
            ps.setString(2, period);
            ps.setString(3, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getInt(1)) : Optional.empty();
            }
        }
    }

    private static void keepBest(Connection c, String course, String period, UUID player, int timeMs, Instant at,
                                 Optional<Integer> previous) throws SQLException {
        if (previous.isPresent() && previous.get() <= timeMs) {
            return;
        }
        String sql = previous.isPresent()
                ? "UPDATE parkour_times SET time_ms = ?, achieved_at = ? WHERE course = ? AND period = ? AND uuid = ?"
                : "INSERT INTO parkour_times (time_ms, achieved_at, course, period, uuid) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, timeMs);
            ps.setObject(2, LocalDateTime.ofInstant(at, ZoneOffset.UTC));
            ps.setString(3, course);
            ps.setString(4, period);
            ps.setString(5, player.toString());
            ps.executeUpdate();
        }
    }

    private static Optional<Standing> standing(Connection c, String course, String period, UUID player)
            throws SQLException {
        // Same order as top(): time, then who got there first, then uuid.
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT me.time_ms, (SELECT COUNT(*) FROM parkour_times o WHERE o.course = me.course"
                        + " AND o.period = me.period AND (o.time_ms < me.time_ms OR (o.time_ms = me.time_ms"
                        + " AND (o.achieved_at < me.achieved_at OR (o.achieved_at = me.achieved_at AND o.uuid < me.uuid)))))"
                        + " FROM parkour_times me WHERE me.course = ? AND me.period = ? AND me.uuid = ?")) {
            ps.setString(1, course);
            ps.setString(2, period);
            ps.setString(3, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new Standing(rs.getInt(1), rs.getInt(2) + 1)) : Optional.empty();
            }
        }
    }
}
