package us.rapidskies.common.tailwind;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.OptionalLong;
import java.util.UUID;
import us.rapidskies.common.db.Database;

/**
 * Tailwind, the rested bonus (PLAN.md §5.3): time away from the network banks bonus time
 * ({@code players.tailwind_banked}, in seconds) that is spent while playing a gamemode,
 * where it boosts mode XP and Trade income.
 *
 * <p>Only the proxy sees real network sessions (a server switch is a quit on one backend
 * and a login on another, in no fixed order), so the proxy owns accrual: it stamps
 * {@code last_seen} on disconnect ({@link #markLeft}) and converts the time since into
 * Tailwind on the next login ({@link #settleLogin}), clearing the stamp so the same absence
 * is never counted twice. Backends only ever subtract ({@link #spend}), with relative
 * updates, so accrual and spending can't overwrite each other.
 *
 * <p>Every method blocks: call from async contexts only.
 */
public final class TailwindBank {

    private static final long DAY_SECONDS = Duration.ofDays(1).toSeconds();

    private final Database db;

    public TailwindBank(Database db) {
        this.db = db;
    }

    /**
     * How fast Tailwind accrues: {@code perOfflineDay} of bonus per day away, counting at most
     * {@code maxDays} days of absence (PLAN.md §5.3: "up to 3 days banked").
     */
    public record Rules(Duration perOfflineDay, int maxDays) {

        public Rules {
            if (perOfflineDay.isNegative() || maxDays < 0) {
                throw new IllegalArgumentException("Tailwind rules must not be negative");
            }
        }

        /** The most Tailwind accrual can bank. */
        public long capSeconds() {
            return perOfflineDay.toSeconds() * maxDays;
        }

        /**
         * The bank after {@code offlineSeconds} away. Accrual never pushes the bank past the
         * cap, but never lowers a bank that is already above it (a starter grant or a staff
         * top-up).
         */
        public long accrue(long banked, long offlineSeconds) {
            if (offlineSeconds <= 0) {
                return banked;
            }
            long counted = Math.min(offlineSeconds, DAY_SECONDS * maxDays);
            long earned = Math.multiplyExact(counted, perOfflineDay.toSeconds()) / DAY_SECONDS;
            return Math.max(banked, Math.min(capSeconds(), banked + earned));
        }
    }

    /**
     * Converts the time since {@link #markLeft} into Tailwind and clears the stamp. Returns
     * the new bank, or empty when there's nothing to settle (first join, or the stamp was
     * already cleared). Uses the database clock, so proxy clock drift doesn't matter.
     */
    public OptionalLong settleLogin(UUID player, Rules rules) throws SQLException {
        return db.transaction(c -> {
            long banked;
            long offline;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT tailwind_banked, TIMESTAMPDIFF(SECOND, last_seen, CURRENT_TIMESTAMP(3)) FROM players"
                            + " WHERE uuid = ? AND last_seen IS NOT NULL FOR UPDATE")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return OptionalLong.empty();
                    }
                    banked = rs.getLong(1);
                    offline = rs.getLong(2);
                }
            }
            long next = rules.accrue(banked, offline);
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE players SET tailwind_banked = ?, last_seen = NULL WHERE uuid = ?")) {
                ps.setLong(1, next);
                ps.setString(2, player.toString());
                ps.executeUpdate();
            }
            return OptionalLong.of(next);
        });
    }

    /** Stamps the moment the player left the network. */
    public void markLeft(UUID player) throws SQLException {
        db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE players SET last_seen = CURRENT_TIMESTAMP(3) WHERE uuid = ?")) {
                ps.setString(1, player.toString());
                return ps.executeUpdate();
            }
        });
    }

    /** Takes up to {@code seconds} from the bank (never below zero). */
    public void spend(UUID player, long seconds) throws SQLException {
        if (seconds <= 0) {
            return;
        }
        db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE players SET tailwind_banked = GREATEST(0, tailwind_banked - ?) WHERE uuid = ?")) {
                ps.setLong(1, seconds);
                ps.setString(2, player.toString());
                return ps.executeUpdate();
            }
        });
    }

    /** The current bank, or 0 for a player who has never joined. */
    public long banked(UUID player) throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT tailwind_banked FROM players WHERE uuid = ?")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            }
        });
    }
}
