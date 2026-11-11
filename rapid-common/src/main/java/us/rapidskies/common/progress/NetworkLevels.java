package us.rapidskies.common.progress;

/**
 * The network level curve (PLAN.md §7): going from level {@code L} to {@code L + 1} takes
 * {@code base + step × (L − 1)} XP, never more than {@code cap}, up to {@code maxLevel}.
 * Levels start at 1 with 0 XP.
 */
public record NetworkLevels(long base, long step, long cap, int maxLevel) {

    public NetworkLevels {
        if (base <= 0 || step < 0 || cap < base || maxLevel < 2) {
            throw new IllegalArgumentException("levels need base > 0, step >= 0, cap >= base and max-level >= 2");
        }
    }

    /** XP needed to go from {@code level} to the next one. */
    public long toNext(int level) {
        return Math.min(cap, base + step * (Math.max(1, level) - 1));
    }

    /** Total XP at which {@code level} is reached. */
    public long totalFor(int level) {
        long total = 0;
        for (int l = 1; l < Math.min(level, maxLevel); l++) {
            total += toNext(l);
        }
        return total;
    }

    public int levelOf(long xp) {
        int level = 1;
        long left = xp;
        while (level < maxLevel && left >= toNext(level)) {
            left -= toNext(level);
            level++;
        }
        return level;
    }

    /** Where {@code xp} stands: the level, XP into it and XP it takes (0 at the top level). */
    public record Progress(int level, long into, long needed) {
        public boolean maxed() {
            return needed == 0;
        }
    }

    public Progress progress(long xp) {
        int level = levelOf(xp);
        if (level >= maxLevel) {
            return new Progress(level, 0, 0);
        }
        return new Progress(level, xp - totalFor(level), toNext(level));
    }
}
