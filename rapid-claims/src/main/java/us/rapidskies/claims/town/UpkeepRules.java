package us.rapidskies.claims.town;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import us.rapidskies.claims.model.Town;

/**
 * Town upkeep and ruins (PLAN.md §4.1), as pure functions of a town and the time.
 *
 * <ul>
 *   <li>Upkeep is charged a day at a time; {@code upkeepPaidUntil} moves a day per payment.</li>
 *   <li>A town {@code graceDays} behind becomes ruins: still protected, but it can't claim.</li>
 *   <li>Ruins {@code ruinsDays} old are deleted; builds stay in the world.</li>
 *   <li>Ruins that pay a day of upkeep are restored, and their backlog is forgiven, so a
 *       returning town isn't asked for weeks of upkeep at once.</li>
 * </ul>
 */
public final class UpkeepRules {

    private UpkeepRules() {
    }

    public static boolean due(Town t, Instant now) {
        return !t.upkeepPaidUntil().isAfter(now);
    }

    /** After one successful day's charge. */
    public static Town paid(Town t, Instant now) {
        if (t.ruined()) {
            return t.withUpkeep(now.plus(Duration.ofDays(1)), Town.State.ACTIVE, Optional.empty());
        }
        return t.withUpkeep(t.upkeepPaidUntil().plus(Duration.ofDays(1)), Town.State.ACTIVE, Optional.empty());
    }

    /**
     * After a charge failed (the bank is short): the town as it should now be, or empty if
     * its ruins have expired and it should be deleted.
     */
    public static Optional<Town> unpaid(Town t, Instant now, int graceDays, int ruinsDays) {
        if (t.ruined()) {
            Instant since = t.ruinsSince().orElse(now);
            return since.plus(Duration.ofDays(ruinsDays)).isAfter(now) ? Optional.of(t) : Optional.empty();
        }
        Duration behind = Duration.between(t.upkeepPaidUntil(), now);
        if (behind.compareTo(Duration.ofDays(graceDays)) >= 0) {
            return Optional.of(t.withUpkeep(t.upkeepPaidUntil(), Town.State.RUINS, Optional.of(now)));
        }
        return Optional.of(t);
    }

    /** Whether a player's personal land, in ruins since {@code since}, should now be released. */
    public static boolean personalRuinsExpired(Instant since, Instant now, int ruinsDays) {
        return !since.plus(Duration.ofDays(ruinsDays)).isAfter(now);
    }
}
