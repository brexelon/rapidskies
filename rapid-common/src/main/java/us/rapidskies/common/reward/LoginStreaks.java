package us.rapidskies.common.reward;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import us.rapidskies.common.db.Database;

/**
 * The daily login streak (PLAN.md §6.2): consecutive UTC days with a login, kept in
 * {@code players.login_streak} and {@code streak_day}. Missing a whole day starts it again.
 */
public final class LoginStreaks {

    private final Database db;

    public LoginStreaks(Database db) {
        this.db = db;
    }

    /** {@code streak} as it stands today, and whether this is the first login of the day. */
    public record Day(int streak, boolean firstToday) {
    }

    /** The streak after a login on {@code today}, given the last one. */
    public static int next(int streak, LocalDate last, LocalDate today) {
        if (last == null) {
            return 1;
        }
        if (last.equals(today)) {
            return Math.max(1, streak);
        }
        return last.plusDays(1).equals(today) ? streak + 1 : 1;
    }

    /** Counts a login on {@code today}. Empty if the player has no profile row. */
    public Optional<Day> record(UUID player, LocalDate today) throws SQLException {
        return db.transaction(c -> {
            int streak;
            LocalDate last;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT login_streak, streak_day FROM players WHERE uuid = ? FOR UPDATE")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    streak = rs.getInt(1);
                    last = rs.getObject(2, LocalDate.class);
                }
            }
            if (today.equals(last)) {
                return Optional.of(new Day(Math.max(1, streak), false));
            }
            int now = next(streak, last, today);
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE players SET login_streak = ?, streak_day = ? WHERE uuid = ?")) {
                ps.setInt(1, now);
                ps.setObject(2, today);
                ps.setString(3, player.toString());
                ps.executeUpdate();
            }
            return Optional.of(new Day(now, true));
        });
    }

    /** The streak as of {@code today} (0 if it has lapsed), for displays. */
    public int current(UUID player, LocalDate today) throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT login_streak, streak_day FROM players WHERE uuid = ?")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return 0;
                    }
                    LocalDate last = rs.getObject(2, LocalDate.class);
                    boolean live = last != null && !last.plusDays(1).isBefore(today);
                    return live ? rs.getInt(1) : 0;
                }
            }
        });
    }
}
