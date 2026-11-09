package us.rapidskies.common.grant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import us.rapidskies.common.grant.GrantTerms.Change;
import us.rapidskies.common.grant.GrantTerms.Placement;
import us.rapidskies.common.grant.GrantTerms.Revocation;

class GrantTermsTest {

    private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");
    private static final Duration MONTH = Duration.ofDays(30);

    @Test
    void aFirstTimedGrantStartsNow() {
        Placement p = GrantTerms.place(Optional.empty(), MONTH, NOW);
        assertEquals(NOW, p.start());
        assertEquals(NOW.plus(MONTH), p.end());
        assertEquals(Hold.until(NOW.plus(MONTH)), p.hold());
    }

    @Test
    void timedGrantsStackOnTimeAlreadyHeld() {
        Instant day29 = NOW.plus(Duration.ofDays(1));
        Placement p = GrantTerms.place(Optional.of(Hold.until(day29)), MONTH, NOW);
        assertEquals(day29, p.start(), "starts when the current time runs out");
        assertEquals(Hold.until(day29.plus(MONTH)), p.hold());
    }

    @Test
    void anExpiredHoldCountsAsNone() {
        Placement p = GrantTerms.place(Optional.of(Hold.until(NOW.minusSeconds(1))), MONTH, NOW);
        assertEquals(NOW, p.start());
    }

    @Test
    void permanentWins() {
        assertEquals(Hold.PERMANENT, GrantTerms.place(Optional.of(Hold.until(NOW.plus(MONTH))), null, NOW).hold());
        Placement onTop = GrantTerms.place(Optional.of(Hold.PERMANENT), MONTH, NOW);
        assertEquals(Hold.PERMANENT, onTop.hold(), "a timed grant can't shorten a permanent hold");
        assertEquals(NOW.plus(MONTH), onTop.end(), "but its own time is still recorded");
        assertNull(GrantTerms.place(Optional.empty(), null, NOW).end());
    }

    @Test
    void revokingTakesAwayOnlyTheTimeLeft() {
        // 30 days granted 10 days ago, plus 15 days from the store stacked on top.
        Grant g = timed(1, NOW.minus(Duration.ofDays(10)), NOW.plus(Duration.ofDays(20)));
        Hold hold = Hold.until(NOW.plus(Duration.ofDays(35)));
        Revocation r = GrantTerms.revoke(Optional.of(hold), g, List.of(), NOW);
        assertEquals(Change.SET, r.change());
        assertEquals(Hold.until(NOW.plus(Duration.ofDays(15))), r.hold(), "the store's 15 days survive");
        assertEquals(Duration.ofDays(20), r.shift());
    }

    @Test
    void revokingAQueuedGrantTakesAllOfIt() {
        Grant first = timed(1, NOW.minus(Duration.ofDays(5)), NOW.plus(Duration.ofDays(25)));
        Grant queued = timed(2, first.expiresAt(), first.expiresAt().plus(MONTH));
        Revocation r = GrantTerms.revoke(Optional.of(Hold.until(queued.expiresAt())), queued, List.of(first), NOW);
        assertEquals(Hold.until(first.expiresAt()), r.hold());
        assertEquals(MONTH, r.shift());
    }

    @Test
    void revokingTheOnlyTimeRemovesTheHold() {
        Grant g = timed(1, NOW, NOW.plus(MONTH));
        Revocation r = GrantTerms.revoke(Optional.of(Hold.until(NOW.plus(MONTH))), g, List.of(), NOW);
        assertEquals(Change.REMOVE, r.change());
    }

    @Test
    void nothingToDoWhenTheHoldIsGoneOrPermanent() {
        Grant g = timed(1, NOW, NOW.plus(MONTH));
        assertEquals(Change.KEEP, GrantTerms.revoke(Optional.empty(), g, List.of(), NOW).change());
        assertEquals(Change.KEEP, GrantTerms.revoke(Optional.of(Hold.PERMANENT), g, List.of(), NOW).change(),
                "someone else's permanent hold stays");
    }

    @Test
    void revokingThePermanentGrantFallsBackToTimedOnes() {
        Grant perm = permanent(1);
        Grant timed = timed(2, NOW, NOW.plus(MONTH));
        Revocation r = GrantTerms.revoke(Optional.of(Hold.PERMANENT), perm, List.of(timed), NOW);
        assertEquals(Hold.until(NOW.plus(MONTH)), r.hold());

        assertEquals(Change.REMOVE, GrantTerms.revoke(Optional.of(Hold.PERMANENT), perm, List.of(), NOW).change());
        assertEquals(Change.KEEP,
                GrantTerms.revoke(Optional.of(Hold.PERMANENT), perm, List.of(permanent(3)), NOW).change(),
                "another permanent grant still holds it");
    }

    private static Grant timed(long id, Instant start, Instant end) {
        return grant(id, start, end);
    }

    private static Grant permanent(long id) {
        return grant(id, NOW.minus(Duration.ofDays(1)), null);
    }

    private static Grant grant(long id, Instant start, Instant end) {
        return new Grant(id, UUID.randomUUID(), Grant.Kind.RANK, "rapidplus", null, "Console", null, null,
                NOW.minus(Duration.ofDays(30)), start, end, null, null, null, null);
    }
}
