package us.rapidskies.common.grant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import us.rapidskies.common.grant.GrantRepository.Duplicate;
import us.rapidskies.common.grant.GrantRepository.Inserted;
import us.rapidskies.common.grant.GrantRepository.NewGrant;
import us.rapidskies.common.testing.TestServices;

@EnabledIf("us.rapidskies.common.testing.TestServices#dockerAvailable")
class GrantRepositoryIT {

    private static final Duration MONTH = Duration.ofDays(30);

    private final UUID steve = UUID.randomUUID();
    private final UUID alex = UUID.randomUUID();
    private final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private GrantRepository grants;

    @BeforeEach
    void setUp() throws Exception {
        TestServices.truncate("grants");
        grants = new GrantRepository(TestServices.database());
    }

    @Test
    void storesAndReadsBackEveryField() throws Exception {
        Grant g = insert(rank(now, now.plus(MONTH), "comp", null));
        assertEquals(steve, g.player());
        assertEquals(Grant.Kind.RANK, g.kind());
        assertEquals("rapidplus", g.value());
        assertEquals(alex, g.grantedBy());
        assertEquals("Alex", g.grantedByName());
        assertEquals("comp", g.reason());
        assertEquals(now, g.startsAt());
        assertEquals(now.plus(MONTH), g.expiresAt());
        assertEquals(Grant.Status.ACTIVE, g.status(now));
        assertEquals(g, grants.find(g.id()).orElseThrow());
    }

    @Test
    void aTransactionIdIsOnlyGrantedOnce() throws Exception {
        Grant first = insert(rank(now, now.plus(MONTH), null, "tbx-1"));
        int[] effects = {0};
        var again = grants.insert(rank(now, now.plus(MONTH), null, "tbx-1"), () -> effects[0]++);
        assertEquals(first, assertInstanceOf(Duplicate.class, again).existing());
        assertEquals(0, effects[0], "the permission system isn't touched twice");
        assertEquals(1, grants.count(steve));
    }

    @Test
    void aFailedEffectLeavesNoRow() throws Exception {
        assertThrows(SQLException.class, () -> grants.insert(rank(now, now.plus(MONTH), null, null), () -> {
            throw new SQLException("LuckPerms is down");
        }));
        assertEquals(0, grants.count(steve));
    }

    @Test
    void liveSkipsExpiredAndRevokedGrants() throws Exception {
        insert(rank(now.minus(Duration.ofDays(60)), now.minus(MONTH), null, null));
        Grant revoked = insert(rank(now, now.plus(MONTH), null, null));
        Grant kept = insert(rank(now, null, null, null));
        grants.revoke(revoked, alex, "Alex", "mistake", now, Duration.ZERO, () -> { });
        assertEquals(java.util.List.of(kept), grants.live(steve, Grant.Kind.RANK, "rapidplus", now));
        assertEquals(3, grants.history(steve, 10, 0).size());
        assertEquals(kept, grants.history(steve, 1, 0).getFirst(), "newest first");
    }

    @Test
    void revokingShiftsLaterStackedGrantsAndOnlyHappensOnce() throws Exception {
        Grant first = insert(rank(now, now.plus(MONTH), null, null));
        Grant stacked = insert(rank(first.expiresAt(), first.expiresAt().plus(MONTH), null, null));

        assertTrue(grants.revoke(first, alex, "Alex", "mistake", now, MONTH, () -> { }));
        Grant moved = grants.find(stacked.id()).orElseThrow();
        assertEquals(now, moved.startsAt());
        assertEquals(now.plus(MONTH), moved.expiresAt());
        Grant gone = grants.find(first.id()).orElseThrow();
        assertEquals(Grant.Status.REVOKED, gone.status(now));
        assertEquals("mistake", gone.revokeReason());

        assertFalse(grants.revoke(first, alex, "Alex", "again", now, MONTH, () -> { }), "already revoked");
        assertEquals(moved, grants.find(stacked.id()).orElseThrow(), "no second shift");
    }

    private Grant insert(NewGrant g) throws Exception {
        return assertInstanceOf(Inserted.class, grants.insert(g, () -> { })).grant();
    }

    private NewGrant rank(Instant start, Instant end, String reason, String txn) {
        return new NewGrant(steve, Grant.Kind.RANK, "rapidplus", alex, "Alex", reason, txn, now, start, end);
    }
}
