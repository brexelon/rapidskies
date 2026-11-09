package us.rapidskies.proxy.party;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import us.rapidskies.proxy.party.Parties.Departure;
import us.rapidskies.proxy.party.Parties.Failure;
import us.rapidskies.proxy.party.Parties.PartyView;

class PartiesTest {

    private static final long TTL = 60_000;

    private final Parties parties = new Parties();
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final UUID c = UUID.randomUUID();

    @Test
    void inviteCreatesThePartyAndAcceptJoinsIt() {
        assertTrue(parties.invite(a, b, 4, 0, TTL).ok());
        assertEquals(List.of(a), parties.of(a).orElseThrow().members(), "party exists from the first invite");
        assertTrue(parties.of(b).isEmpty(), "not a member until they accept");

        PartyView joined = parties.accept(b, a, 4, 1).value();
        assertEquals(List.of(a, b), joined.members());
        assertTrue(joined.isLeader(a));
        assertEquals(joined.id(), parties.of(b).orElseThrow().id());
    }

    @Test
    void onlyTheLeaderInvitesAndTargetsMustBeFree() {
        parties.invite(a, b, 4, 0, TTL);
        parties.accept(b, a, 4, 0);
        assertEquals(Failure.NOT_LEADER, parties.invite(b, c, 4, 0, TTL).failure());
        assertEquals(Failure.TARGET_IN_PARTY, parties.invite(c, b, 4, 0, TTL).failure());
        assertEquals(Failure.SELF, parties.invite(a, a, 4, 0, TTL).failure());
        parties.invite(a, c, 4, 0, TTL);
        assertEquals(Failure.ALREADY_INVITED, parties.invite(a, c, 4, 0, TTL).failure());
    }

    @Test
    void sizeCountsMembersAndOpenInvites() {
        parties.invite(a, b, 2, 0, TTL);
        assertEquals(Failure.FULL, parties.invite(a, c, 2, 0, TTL).failure(), "one member + one invite = 2");
        assertEquals(Failure.FULL, parties.invite(c, UUID.randomUUID(), 1, 0, TTL).failure(),
                "a size of 1 can't start a party");
    }

    @Test
    void acceptChecksSizeAgain() {
        parties.invite(a, b, 4, 0, TTL);
        parties.invite(a, c, 4, 0, TTL);
        parties.accept(b, a, 4, 0);
        // The leader's rank lapsed meanwhile: their party may only hold 2 now.
        assertEquals(Failure.FULL, parties.accept(c, a, 2, 0).failure());
        assertTrue(parties.of(c).isEmpty());
    }

    @Test
    void expiredInvitesCantBeAcceptedAndLonelyPartiesClose() {
        parties.invite(a, b, 4, 0, TTL);
        assertEquals(Failure.NO_INVITE, parties.accept(b, a, 4, TTL).failure());
        assertTrue(parties.of(a).isEmpty(), "leader alone with no invites: party closed");

        parties.invite(a, b, 4, 0, TTL);
        List<Parties.Expired> expired = parties.expire(TTL + 1);
        assertEquals(1, expired.size());
        assertEquals(b, expired.getFirst().invitee());
        assertTrue(expired.getFirst().disbanded());
        assertTrue(parties.of(a).isEmpty());
    }

    @Test
    void leaderLeavingHandsOverToTheLongestMember() {
        party(a, b, c);
        Departure d = parties.leave(a).orElseThrow();
        assertTrue(d.leaderChanged());
        assertEquals(b, d.after().orElseThrow().leader());
        assertEquals(List.of(b, c), parties.of(c).orElseThrow().members());
    }

    @Test
    void lastMemberLeavingDisbands() {
        party(a, b);
        Departure d = parties.leave(b).orElseThrow();
        assertTrue(d.disbanded());
        assertTrue(parties.of(a).isEmpty());
        assertTrue(parties.leave(a).isEmpty());
    }

    @Test
    void kickTransferDisband() {
        party(a, b, c);
        assertEquals(Failure.NOT_LEADER, parties.kick(b, c).failure());
        assertEquals(Failure.SELF, parties.kick(a, a).failure());
        assertTrue(parties.kick(a, c).ok());
        assertTrue(parties.of(c).isEmpty());

        assertEquals(Failure.NOT_MEMBER, parties.transfer(a, c).failure());
        assertEquals(b, parties.transfer(a, b).value().leader());
        assertEquals(Failure.NOT_LEADER, parties.disband(a).failure());
        assertEquals(List.of(a, b), parties.disband(b).value().members());
        assertFalse(parties.of(a).isPresent());
    }

    @Test
    void denyTellsWhichPartyAndCloses() {
        parties.invite(a, b, 4, 0, TTL);
        assertEquals(a, parties.deny(b, a).orElseThrow().leader());
        assertTrue(parties.of(a).isEmpty());
        assertTrue(parties.deny(b, a).isEmpty());
    }

    @Test
    void invitesForListsOpenInvitesOnly() {
        parties.invite(a, c, 4, 0, TTL);
        parties.invite(b, c, 4, 0, 10);
        assertEquals(List.of(a), parties.invitesFor(c, 20));
    }

    private void party(UUID leader, UUID... members) {
        for (UUID m : members) {
            assertTrue(parties.invite(leader, m, 12, 0, TTL).ok());
            assertTrue(parties.accept(m, leader, 12, 0).ok());
        }
    }
}
