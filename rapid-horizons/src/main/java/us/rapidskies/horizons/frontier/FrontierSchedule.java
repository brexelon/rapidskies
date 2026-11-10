package us.rapidskies.horizons.frontier;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The calendar of the Frontier rotation (PLAN.md §5.2): a Frontier is open for a month, the
 * last week of it carries a reset warning (and the next one is generated meanwhile), and
 * then it's replaced. Free of Bukkit so every edge is a unit test.
 */
public final class FrontierSchedule {

    private FrontierSchedule() {
    }

    /** Where the open Frontier is in its life. */
    public enum Phase {
        /** Open, the reset is still more than the warning away. */
        OPEN,
        /** Open, resetting within the warning: players are told, the next one is generated. */
        WARNING,
        /** Past its closing time: rotate now. */
        DUE
    }

    public static Phase phase(Instant now, Instant closesAt, Duration warning) {
        if (!now.isBefore(closesAt)) {
            return Phase.DUE;
        }
        return now.isBefore(closesAt.minus(warning)) ? Phase.OPEN : Phase.WARNING;
    }

    /**
     * The modifier after {@code last} in {@code cycle}: the next one in the list, wrapping
     * around, so a modifier never runs twice in a row unless it's the only one. The first
     * Frontier ({@code last} empty) takes the first in the list.
     */
    public static Modifier next(List<Modifier> cycle, Optional<Modifier> last) {
        if (cycle.isEmpty()) {
            throw new IllegalArgumentException("no modifiers to choose from");
        }
        if (last.isEmpty()) {
            return cycle.getFirst();
        }
        int at = cycle.indexOf(last.get());
        return cycle.get((at + 1) % cycle.size());
    }

    /**
     * The Chapter of the {@code number}th Frontier (1-based): chapters are used in order, one
     * per Frontier, and once the list runs out new Frontiers have none until one is added.
     */
    public static Optional<String> chapter(int number, List<String> chapters) {
        return number >= 1 && number <= chapters.size() ? Optional.of(chapters.get(number - 1)) : Optional.empty();
    }

    /** Whether the End of a Frontier has opened. Null: no End (or it's not scheduled). */
    public static boolean endOpen(Instant now, Instant endOpensAt) {
        return endOpensAt != null && !now.isBefore(endOpensAt);
    }

    /** Time left until {@code at}, never negative. */
    public static Duration until(Instant now, Instant at) {
        return at.isAfter(now) ? Duration.between(now, at) : Duration.ZERO;
    }
}
