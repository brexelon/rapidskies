package us.rapidskies.claims.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Who may do what where (PLAN.md §4.1): personal land, trust, towns, plots, denials and borders. */
class LandIndexTest {

    private static final String W = "minecraft:overworld";

    private final LandIndex land = new LandIndex();
    private final UUID steve = UUID.randomUUID();
    private final UUID alex = UUID.randomUUID();
    private final UUID notch = UUID.randomUUID();
    private final UUID stranger = UUID.randomUUID();

    private Claim claim(int x, int z, Owner owner) {
        Claim c = new Claim(new ChunkKey(W, x, z), owner, Flag.MOB_SPAWNING.bit, owner.id(), Instant.now());
        land.put(c);
        return c;
    }

    private Town town(UUID mayor, Map<UUID, TownRole> members, ChunkKey center) {
        Town t = new Town(UUID.randomUUID(), "Riverside", "RIV", TagColor.AQUA, mayor, center, Optional.empty(),
                Instant.now(), Town.State.ACTIVE, Optional.empty(), members, Town.defaultRoles(), Set.of());
        land.putTown(t);
        return t;
    }

    @Test
    void wildernessIsUnprotected() {
        assertEquals(Role.OWNER, land.role(stranger, new ChunkKey(W, 5, 5)));
        assertFalse(land.isDenied(stranger, new ChunkKey(W, 5, 5)));
    }

    @Test
    void personalLandAndTrust() {
        Owner owner = Owner.player(steve);
        ChunkKey k = claim(0, 0, owner).chunk();
        assertEquals(Role.OWNER, land.role(steve, k));
        assertEquals(Role.VISITOR, land.role(stranger, k));

        land.setAccess(owner, land.access(owner).withTrust(alex, Role.BUILDER));
        assertEquals(Role.BUILDER, land.role(alex, k));
        assertEquals(Role.VISITOR, land.role(stranger, k));

        land.setAccess(owner, land.access(owner).withTrust(Access.EVERYONE, Role.TRUSTED));
        assertEquals(Role.TRUSTED, land.role(stranger, k), "the * entry reaches everyone");
        assertEquals(Role.BUILDER, land.role(alex, k), "a personal entry above * wins");

        land.setAccess(owner, land.access(owner).withTrust(alex, null));
        assertEquals(Role.TRUSTED, land.role(alex, k));
    }

    @Test
    void trustCoversAllOfAnOwnersLand() {
        Owner owner = Owner.player(steve);
        claim(0, 0, owner);
        claim(10, 10, owner);
        land.setAccess(owner, land.access(owner).withTrust(alex, Role.MEMBER));
        assertEquals(Role.MEMBER, land.role(alex, new ChunkKey(W, 10, 10)));
        assertEquals(2, land.countOf(owner));
    }

    @Test
    void deniedPlayersAreKeptOutUnlessTrusted() {
        Owner owner = Owner.player(steve);
        ChunkKey k = claim(0, 0, owner).chunk();
        land.setAccess(owner, land.access(owner).withDenied(stranger, true));
        assertTrue(land.isDenied(stranger, k));
        assertFalse(land.isDenied(steve, k), "never the owner");
        assertFalse(land.isDenied(stranger, new ChunkKey(W, 1, 0)), "only on their land");
        land.setAccess(owner, land.access(owner).withTrust(stranger, Role.TRUSTED));
        assertFalse(land.isDenied(stranger, k), "trust beats an old denial");
    }

    @Test
    void townLandFollowsTownRoles() {
        ChunkKey center = new ChunkKey(W, 0, 0);
        Town t = town(steve, Map.of(steve, TownRole.MAYOR, alex, TownRole.RESIDENT, notch, TownRole.RECRUIT), center);
        claim(0, 0, Owner.town(t.id()));
        assertEquals(Role.OWNER, land.role(steve, center), "mayor");
        assertEquals(Role.BUILDER, land.role(alex, center), "resident default land role");
        assertEquals(Role.TRUSTED, land.role(notch, center), "recruit default land role");
        assertEquals(Role.VISITOR, land.role(stranger, center));

        land.setAccess(Owner.town(t.id()), Access.NONE.withTrust(stranger, Role.MEMBER));
        assertEquals(Role.MEMBER, land.role(stranger, center), "the town's own trust list for outsiders");
    }

    @Test
    void plotsBelongToTheirResident() {
        ChunkKey center = new ChunkKey(W, 0, 0);
        ChunkKey plotChunk = new ChunkKey(W, 1, 0);
        Town t = town(steve, Map.of(steve, TownRole.MAYOR, alex, TownRole.RESIDENT, notch, TownRole.ASSISTANT), center);
        claim(0, 0, Owner.town(t.id()));
        claim(1, 0, Owner.town(t.id()));
        land.putPlot(new Plot(plotChunk, t.id(), alex));

        assertEquals(Role.OWNER, land.role(alex, plotChunk));
        assertEquals(Role.TRUSTED, land.role(steve, plotChunk), "the mayor only gets doors in a resident's plot");
        assertEquals(Role.TRUSTED, land.role(notch, plotChunk), "so do assistants");
        assertEquals(Role.VISITOR, land.role(stranger, plotChunk));

        land.setAccess(Owner.player(alex), Access.NONE.withTrust(stranger, Role.BUILDER));
        assertEquals(Role.BUILDER, land.role(stranger, plotChunk), "the resident's own trust list applies");
        land.setAccess(Owner.town(t.id()), Access.NONE.withTrust(UUID.randomUUID(), Role.CO_OWNER));
        assertEquals(Role.BUILDER, land.role(alex, center), "outside the plot alex is just a resident");

        land.remove(plotChunk);
        assertTrue(land.plot(plotChunk).isEmpty(), "unclaiming drops the plot");
    }

    @Test
    void townTrustIsCappedInsidePlots() {
        ChunkKey plotChunk = new ChunkKey(W, 0, 0);
        Town t = town(steve, Map.of(steve, TownRole.MAYOR, alex, TownRole.RESIDENT), plotChunk);
        claim(0, 0, Owner.town(t.id()));
        land.putPlot(new Plot(plotChunk, t.id(), alex));
        land.setAccess(Owner.town(t.id()), Access.NONE.withTrust(stranger, Role.BUILDER));
        assertEquals(Role.TRUSTED, land.role(stranger, plotChunk));
    }

    @Test
    void crossingBorders() {
        Owner a = Owner.player(steve);
        Owner b = Owner.player(alex);
        claim(0, 0, a);
        claim(1, 0, a);
        claim(2, 0, b);
        ChunkKey wild = new ChunkKey(W, 0, 5);
        assertTrue(land.crossAllowed(new ChunkKey(W, 0, 0), new ChunkKey(W, 1, 0)), "same owner");
        assertTrue(land.crossAllowed(new ChunkKey(W, 0, 0), wild), "out into wilderness");
        assertFalse(land.crossAllowed(wild, new ChunkKey(W, 0, 0)), "wilderness into a claim");
        assertFalse(land.crossAllowed(new ChunkKey(W, 1, 0), new ChunkKey(W, 2, 0)), "between owners");
        assertTrue(land.crossAllowed(new ChunkKey(W, 2, 0), new ChunkKey(W, 2, 0)), "within a chunk");
    }

    @Test
    void townLandMustStayConnected() {
        ChunkKey center = new ChunkKey(W, 0, 0);
        Town t = town(steve, Map.of(steve, TownRole.MAYOR), center);
        Owner o = Owner.town(t.id());
        // A line: center - (1,0) - (2,0), plus (1,1) hanging off the middle.
        claim(0, 0, o);
        claim(1, 0, o);
        claim(2, 0, o);
        claim(1, 1, o);
        assertTrue(land.touchesTown(t.id(), new ChunkKey(W, 3, 0)));
        assertFalse(land.touchesTown(t.id(), new ChunkKey(W, 3, 1)), "corners don't count");
        assertFalse(land.staysConnectedWithout(t, new ChunkKey(W, 1, 0)), "the middle holds the rest on");
        assertTrue(land.staysConnectedWithout(t, new ChunkKey(W, 2, 0)), "an end can go");
        assertTrue(land.staysConnectedWithout(t, new ChunkKey(W, 1, 1)));
        assertFalse(land.staysConnectedWithout(t, center), "never the center");
    }

    @Test
    void townIndexesFollowMembership() {
        ChunkKey center = new ChunkKey(W, 0, 0);
        Town t = town(steve, Map.of(steve, TownRole.MAYOR, alex, TownRole.RESIDENT), center);
        assertEquals(Optional.of(t), land.townOf(alex));
        assertEquals(Optional.of(t), land.townByName("riverside"), "names ignore case");

        Town renamed = t.withMember(alex, null).withName("Hillside");
        land.putTown(renamed);
        assertTrue(land.townOf(alex).isEmpty(), "removed members leave the index");
        assertTrue(land.townByName("Riverside").isEmpty(), "the old name is freed");
        assertEquals(Optional.of(renamed), land.townByName("HILLSIDE"));

        land.removeTown(t.id());
        assertTrue(land.townOf(steve).isEmpty());
    }

    @Test
    void reassigningAChunkMovesItBetweenOwners() {
        Owner a = Owner.player(steve);
        Claim c = claim(0, 0, a);
        Town t = town(steve, Map.of(steve, TownRole.MAYOR), c.chunk());
        land.put(c.withOwner(Owner.town(t.id())));
        assertEquals(0, land.countOf(a));
        assertEquals(1, land.countOf(Owner.town(t.id())));
    }

    @Test
    void townPermissionsAndDefaults() {
        Town t = town(steve, Map.of(steve, TownRole.MAYOR, alex, TownRole.ASSISTANT, notch, TownRole.RECRUIT),
                new ChunkKey(W, 0, 0));
        for (TownPermission p : TownPermission.values()) {
            assertTrue(t.may(steve, p), "mayors may do everything: " + p);
            assertFalse(t.may(stranger, p));
        }
        assertTrue(t.may(alex, TownPermission.CLAIM));
        assertFalse(t.may(alex, TownPermission.WITHDRAW), "assistants can't empty the bank by default");
        assertFalse(t.may(notch, TownPermission.INVITE));
        Town changed = t.withRole(TownRole.RECRUIT, new Town.RoleSettings(Set.of(TownPermission.INVITE), Role.MEMBER));
        assertTrue(changed.may(notch, TownPermission.INVITE));
        assertEquals(Role.MEMBER, changed.landRole(notch));
        assertTrue(TownRole.MAYOR.above(TownRole.ASSISTANT));
        assertFalse(TownRole.RECRUIT.above(TownRole.RESIDENT));
    }
}
