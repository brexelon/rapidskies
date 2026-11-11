package us.rapidskies.common.progress;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntFunction;
import us.rapidskies.common.cosmetic.CosmeticRepository;
import us.rapidskies.common.db.Database;
import us.rapidskies.common.economy.Ledger;

/**
 * Network XP and the Season Pass (PLAN.md §7). Adding XP is one transaction: the player's
 * {@code network_xp} goes up under a row lock, every level crossed pays its Bolts (once per
 * level ever, by transaction id) and grants its cosmetics, and the current season's pass XP
 * goes up by the same amount, granting the rewards of every tier crossed (premium ones only
 * with the premium track). A crash can't leave XP without its rewards or the other way round.
 * Every method blocks.
 */
public final class ProgressRepository {

    /** What a level gives. */
    public record LevelReward(long bolts, List<String> cosmetics) {
        public static final LevelReward NONE = new LevelReward(0, List.of());
    }

    /** The running season: tier {@code t} is reached at {@code t × xpPerTier} season XP. */
    public record Season(String id, long xpPerTier, int tiers, IntFunction<List<String>> free,
                         IntFunction<List<String>> premium) {

        public int tierOf(long xp) {
            return (int) Math.min(tiers, xp / xpPerTier);
        }

        /** Every premium reward from tier 1 to the tier {@code xp} reaches. */
        public List<String> premiumUpTo(long xp) {
            List<String> out = new ArrayList<>();
            for (int t = 1; t <= tierOf(xp); t++) {
                out.addAll(premium.apply(t));
            }
            return out;
        }
    }

    /** How XP is turned into rewards. */
    public interface Rules {
        NetworkLevels levels();

        LevelReward reward(int level);

        /** Level Bolts are skipped (for good) for accounts that don't earn Bolts (shared IP). */
        boolean payBolts();

        Optional<Season> season();
    }

    /** What an XP gain did. */
    public record Gain(long xpBefore, long xpAfter, int levelBefore, int levelAfter, long boltsPaid,
                       List<String> newCosmetics, String season, int tierBefore, int tierAfter, long seasonXp,
                       boolean premium) {
        public boolean leveledUp() {
            return levelAfter > levelBefore;
        }

        public boolean tieredUp() {
            return tierAfter > tierBefore;
        }
    }

    public record SeasonStanding(long xp, boolean premium) {
        public static final SeasonStanding NONE = new SeasonStanding(0, false);
    }

    private final Database db;
    private final Ledger bolts;

    public ProgressRepository(Database db, Ledger bolts) {
        this.db = db;
        this.bolts = bolts;
    }

    public long xp(UUID player) throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT network_xp FROM players WHERE uuid = ?")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            }
        });
    }

    public SeasonStanding season(UUID player, String season) throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT xp, premium FROM season_progress WHERE uuid = ? AND season = ?")) {
                ps.setString(1, player.toString());
                ps.setString(2, season);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new SeasonStanding(rs.getLong(1), rs.getBoolean(2)) : SeasonStanding.NONE;
                }
            }
        });
    }

    /** Adds {@code amount} (positive) network XP. Empty if the player has no profile row. */
    public Optional<Gain> add(UUID player, long amount, Rules rules) throws SQLException {
        if (amount <= 0) {
            throw new IllegalArgumentException("XP must be positive, got " + amount);
        }
        for (int attempt = 1; ; attempt++) {
            try {
                return db.transaction(c -> addIn(c, player, amount, rules));
            } catch (SQLException e) {
                if (attempt >= 3 || !"40001".equals(e.getSQLState())) {
                    throw e;
                }
            }
        }
    }

    private Optional<Gain> addIn(Connection c, UUID player, long amount, Rules rules) throws SQLException {
        long before;
        try (PreparedStatement ps = c.prepareStatement("SELECT network_xp FROM players WHERE uuid = ? FOR UPDATE")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                before = rs.getLong(1);
            }
        }
        long after = before + amount;
        try (PreparedStatement ps = c.prepareStatement("UPDATE players SET network_xp = ? WHERE uuid = ?")) {
            ps.setLong(1, after);
            ps.setString(2, player.toString());
            ps.executeUpdate();
        }
        NetworkLevels levels = rules.levels();
        int levelBefore = levels.levelOf(before);
        int levelAfter = levels.levelOf(after);
        long paid = 0;
        List<String> granted = new ArrayList<>();
        for (int level = levelBefore + 1; level <= levelAfter; level++) {
            LevelReward reward = rules.reward(level);
            String txn = "level:" + player + ":" + level;
            if (reward.bolts() > 0 && rules.payBolts() && !bolts.hasTxnIn(c, txn)) {
                bolts.creditIn(c, player, reward.bolts(), "level", "levels", txn);
                paid += reward.bolts();
            }
            for (String cosmetic : reward.cosmetics()) {
                if (CosmeticRepository.grantIn(c, player, cosmetic, "level:" + level)) {
                    granted.add(cosmetic);
                }
            }
        }

        Optional<Season> season = rules.season();
        if (season.isEmpty()) {
            return Optional.of(new Gain(before, after, levelBefore, levelAfter, paid, granted, null, 0, 0, 0, false));
        }
        Season s = season.get();
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO season_progress (uuid, season, xp) VALUES (?, ?, ?)"
                + " ON DUPLICATE KEY UPDATE xp = xp + VALUES(xp)")) {
            ps.setString(1, player.toString());
            ps.setString(2, s.id());
            ps.setLong(3, amount);
            ps.executeUpdate();
        }
        long seasonXp;
        boolean premium;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT xp, premium FROM season_progress WHERE uuid = ? AND season = ?")) {
            ps.setString(1, player.toString());
            ps.setString(2, s.id());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                seasonXp = rs.getLong(1);
                premium = rs.getBoolean(2);
            }
        }
        int tierBefore = s.tierOf(seasonXp - amount);
        int tierAfter = s.tierOf(seasonXp);
        for (int tier = tierBefore + 1; tier <= tierAfter; tier++) {
            List<String> rewards = new ArrayList<>(s.free().apply(tier));
            if (premium) {
                rewards.addAll(s.premium().apply(tier));
            }
            for (String cosmetic : rewards) {
                if (CosmeticRepository.grantIn(c, player, cosmetic, "pass:" + s.id())) {
                    granted.add(cosmetic);
                }
            }
        }
        return Optional.of(new Gain(before, after, levelBefore, levelAfter, paid, granted, s.id(), tierBefore,
                tierAfter, seasonXp, premium));
    }
}
