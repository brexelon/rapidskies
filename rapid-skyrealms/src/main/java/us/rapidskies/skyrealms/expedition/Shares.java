package us.rapidskies.skyrealms.expedition;

import java.util.Map;
import java.util.UUID;

/**
 * Splitting an Expedition fairly (PLAN.md §5.1: loot is split per contributor, so there's no
 * last-hit stealing). Pure, so it's unit-tested.
 */
public final class Shares {

    private Shares() {
    }

    /**
     * A player's amount of one heart loot entry: {@code listed} for someone who struck the
     * heart as often as the average striker, in proportion to their hits otherwise, at most
     * double. Who struck last makes no difference.
     */
    public static int heart(int listed, int hits, Map<UUID, Integer> allHits) {
        if (hits <= 0 || allHits.isEmpty()) {
            return 0;
        }
        double mean = allHits.values().stream().mapToInt(Integer::intValue).average().orElse(1);
        long share = Math.round(listed * hits / Math.max(1e-9, mean));
        return (int) Math.max(0, Math.min(2L * listed, share));
    }

    /** Hits the heart needs: {@code perPlayer} for each player there, never below {@code min}. */
    public static int hitsNeeded(int perPlayer, int min, int players) {
        return Math.max(min, perPlayer * Math.max(0, players));
    }

    /** Coins for {@code points}, with Tailwind (rounded down). */
    public static long coins(int points, int perPoint, double tailwind) {
        return (long) Math.floor(points * (long) perPoint * Math.max(1.0, tailwind) + 1e-9);
    }
}
