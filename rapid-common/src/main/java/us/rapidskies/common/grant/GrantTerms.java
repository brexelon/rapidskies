package us.rapidskies.common.grant;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;

/**
 * How grants map onto the permission system's hold for one rank or permission
 * (PLAN.md §6.3: "stacking extends time"). Pure, so it can be tested without LuckPerms.
 *
 * <ul>
 *   <li>A timed grant stacks: it starts when the time the player already holds ends, so a
 *       30-day renewal on day 29 runs to day 60, the same as LuckPerms' {@code accumulate}.
 *       Time that came from elsewhere (the store via {@code lp ... accumulate}) is kept.</li>
 *   <li>A permanent grant makes the hold permanent. A timed grant on top of a permanent
 *       hold is recorded but changes nothing.</li>
 *   <li>Revoking a timed grant takes away only the time it still had left; anything stacked
 *       after it moves earlier by the same amount. Revoking the last permanent grant falls
 *       back to the latest end among the remaining timed grants.</li>
 * </ul>
 */
public final class GrantTerms {

    private GrantTerms() {
    }

    /** Where a new grant's time falls, and the hold afterwards. {@code end} is null if permanent. */
    public record Placement(Instant start, Instant end, Hold hold) {
    }

    /**
     * @param current  the hold now (an expired one counts as none)
     * @param duration null for a permanent grant
     */
    public static Placement place(Optional<Hold> current, Duration duration, Instant now) {
        Optional<Hold> live = current.filter(h -> h.liveAt(now));
        if (duration == null) {
            return new Placement(now, null, Hold.PERMANENT);
        }
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("duration must be positive: " + duration);
        }
        if (live.isPresent() && live.get().permanent()) {
            return new Placement(now, now.plus(duration), Hold.PERMANENT);
        }
        Instant start = live.map(Hold::until).orElse(now);
        Instant end = start.plus(duration);
        return new Placement(start, end, Hold.until(end));
    }

    /** What revoking does to the hold. */
    public enum Change {
        /** Leave the permission system alone (nothing to take away, or something else still holds it). */
        KEEP,
        SET,
        REMOVE
    }

    /**
     * @param hold  the new hold when {@code change} is {@link Change#SET}, otherwise null
     * @param shift how much earlier to move live timed grants of the same thing that start
     *              at or after the revoked grant's end
     */
    public record Revocation(Change change, Hold hold, Duration shift) {
        static final Revocation KEEP = new Revocation(Change.KEEP, null, Duration.ZERO);
    }

    /**
     * @param current the hold now (an expired one counts as none)
     * @param grant   the grant being revoked; must be live
     * @param others  the player's other live grants of the same thing
     */
    public static Revocation revoke(Optional<Hold> current, Grant grant, Collection<Grant> others, Instant now) {
        if (!grant.live(now)) {
            throw new IllegalArgumentException("grant #" + grant.id() + " is not live");
        }
        Optional<Hold> live = current.filter(h -> h.liveAt(now));
        if (live.isEmpty()) {
            return Revocation.KEEP;
        }
        Hold hold = live.get();
        if (grant.permanent()) {
            if (!hold.permanent() || others.stream().anyMatch(g -> g.live(now) && g.permanent())) {
                return Revocation.KEEP;
            }
            return others.stream()
                    .filter(g -> g.live(now))
                    .map(Grant::expiresAt)
                    .filter(Objects::nonNull)
                    .max(Comparator.naturalOrder())
                    .map(end -> new Revocation(Change.SET, Hold.until(end), Duration.ZERO))
                    .orElse(new Revocation(Change.REMOVE, null, Duration.ZERO));
        }
        if (hold.permanent()) {
            return Revocation.KEEP;
        }
        Instant from = grant.startsAt().isAfter(now) ? grant.startsAt() : now;
        Duration left = Duration.between(from, grant.expiresAt());
        Instant newEnd = hold.until().minus(left);
        if (!newEnd.isAfter(now)) {
            return new Revocation(Change.REMOVE, null, left);
        }
        return new Revocation(Change.SET, Hold.until(newEnd), left);
    }
}
