package us.rapidskies.skyrealms.season;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The season leaderboard (PLAN.md §5.1: the island-value leaderboard resets each season while
 * islands persist): islands ranked by the worth they gained since the season began. An island
 * created during the season started from nothing. Pure, so it's unit-tested.
 */
public final class SeasonRanking {

    public record Entry(UUID island, BigDecimal gained) {
    }

    private SeasonRanking() {
    }

    /** Islands that gained worth, best first (ties by id, so the order never flickers). */
    public static List<Entry> rank(Map<UUID, BigDecimal> worths, Map<UUID, BigDecimal> baselines) {
        List<Entry> out = new ArrayList<>();
        worths.forEach((island, worth) -> {
            BigDecimal gained = worth.subtract(baselines.getOrDefault(island, BigDecimal.ZERO));
            if (gained.signum() > 0) {
                out.add(new Entry(island, gained));
            }
        });
        out.sort(Comparator.comparing(Entry::gained).reversed().thenComparing(e -> e.island().toString()));
        return out;
    }

    /** 1-based rank of an island, or 0 if it isn't on the board. */
    public static int rankOf(List<Entry> ranking, UUID island) {
        for (int i = 0; i < ranking.size(); i++) {
            if (ranking.get(i).island().equals(island)) {
                return i + 1;
            }
        }
        return 0;
    }
}
