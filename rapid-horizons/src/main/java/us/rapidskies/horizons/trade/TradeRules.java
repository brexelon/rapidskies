package us.rapidskies.horizons.trade;

import java.time.Duration;
import java.time.Instant;

/**
 * The numbers behind Trades, kept free of Bukkit so they're unit-tested
 * (PLAN.md §5.2, §5.3: soft caps, so a casual player isn't hopelessly behind a no-lifer).
 *
 * @param maxActive         Trades a player may have at once
 * @param maxLevel          levels stop here (XP still counts toward nothing more)
 * @param levelBase         XP from level 0 to 1
 * @param levelStep         each further level costs this much more XP than the last
 * @param incomePercentPerLevel Crowns bonus per level, in percent
 * @param dailyPoints       Trade points per UTC day at full rate; past it, returns halve
 *                          with every further {@code dailyPoints}
 * @param floorPercent      returns never drop below this share
 * @param levelsPerChunk    total Trade levels per extra personal claim chunk
 * @param maxChunks         most claim chunks Trades can give
 */
public record TradeRules(int maxActive, int maxLevel, long levelBase, long levelStep, double incomePercentPerLevel,
                         long dailyPoints, double floorPercent, int levelsPerChunk, int maxChunks) {

    /** XP needed to go from {@code level} to {@code level + 1}. */
    public long xpToNext(int level) {
        return levelBase + levelStep * level;
    }

    /** Total XP needed to reach {@code level} from 0. */
    public long xpForLevel(int level) {
        // Arithmetic series: level × base + step × level(level-1)/2.
        return levelBase * level + levelStep * ((long) level * (level - 1) / 2);
    }

    /** The level {@code totalXp} reaches, at most {@link #maxLevel}. */
    public int levelFor(double totalXp) {
        int level = 0;
        while (level < maxLevel && totalXp >= xpForLevel(level + 1)) {
            level++;
        }
        return level;
    }

    /** Share of the way from {@code level} to the next, 0..1 (1 at the max level). */
    public double progress(double totalXp, int level) {
        if (level >= maxLevel) {
            return 1.0;
        }
        double into = totalXp - xpForLevel(level);
        return Math.max(0, Math.min(1, into / xpToNext(level)));
    }

    /** Crowns multiplier for a Trade at {@code level}. */
    public double incomeMultiplier(int level) {
        return 1.0 + incomePercentPerLevel / 100.0 * Math.min(level, maxLevel);
    }

    /**
     * How much of a reward still counts after {@code pointsToday} points today: all of it
     * below the daily threshold, then half, a quarter... for every further threshold's worth,
     * never below the floor. Applied to both XP and Crowns.
     */
    public double softCap(long pointsToday) {
        if (dailyPoints <= 0 || pointsToday < dailyPoints) {
            return 1.0;
        }
        long beyond = (pointsToday - dailyPoints) / dailyPoints;
        double factor = Math.pow(0.5, 1 + Math.min(beyond, 60));
        return Math.max(floorPercent / 100.0, factor);
    }

    /** What one piece of work pays. */
    public record Reward(double xp, double crowns) {
    }

    /**
     * The reward for work worth {@code baseXp}: the soft cap (from points already earned
     * today) and Tailwind apply to both XP and Crowns; the level bonus to Crowns only.
     */
    public Reward reward(double baseXp, double crownsPerXp, int level, long pointsToday, double tailwind) {
        double factor = softCap(pointsToday) * tailwind;
        return new Reward(baseXp * factor, baseXp * crownsPerXp * incomeMultiplier(level) * factor);
    }

    /** Time until a Trade may be dropped again; zero when it may be dropped now. */
    public static Duration cooldownLeft(Instant lastChange, Instant now,
                                        Duration cooldown) {
        if (lastChange == null) {
            return Duration.ZERO;
        }
        Instant ready = lastChange.plus(cooldown);
        return ready.isAfter(now) ? Duration.between(now, ready) : Duration.ZERO;
    }

    /** Extra personal claim chunks for a player whose Trades add up to {@code totalLevels}. */
    public int claimChunks(int totalLevels) {
        if (levelsPerChunk <= 0) {
            return 0;
        }
        return Math.min(maxChunks, Math.max(0, totalLevels) / levelsPerChunk);
    }
}
