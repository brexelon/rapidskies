package us.rapidskies.claims.data;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.mariadb.MariaDBContainer;
import us.rapidskies.claims.model.Access;
import us.rapidskies.claims.model.ChunkKey;
import us.rapidskies.claims.model.Claim;
import us.rapidskies.claims.model.Flag;
import us.rapidskies.claims.model.Owner;
import us.rapidskies.claims.model.Plot;
import us.rapidskies.claims.model.Role;
import us.rapidskies.claims.model.TagColor;
import us.rapidskies.claims.model.Town;
import us.rapidskies.claims.model.TownPermission;
import us.rapidskies.claims.model.TownRole;
import us.rapidskies.common.config.DatabaseSettings;
import us.rapidskies.common.db.Database;

/**
 * Every statement {@link ClaimRepository} runs, against a real MariaDB with the {@code core}
 * and {@code claims} migrations applied. Skipped without Docker; runs in CI.
 */
@EnabledIf("dockerAvailable")
class ClaimRepositoryIT {

    private static final String W = "minecraft:overworld";

    private static MariaDBContainer mariadb;
    private static Database db;
    private ClaimRepository repo;
    private final UUID steve = UUID.randomUUID();
    private final UUID alex = UUID.randomUUID();

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException e) {
            return false;
        }
    }

    @BeforeAll
    static void start() throws Exception {
        mariadb = new MariaDBContainer("mariadb:11.4");
        mariadb.start();
        db = Database.connect(new DatabaseSettings(mariadb.getHost(), mariadb.getMappedPort(3306),
                mariadb.getDatabaseName(), mariadb.getUsername(), mariadb.getPassword(), 4), "claims-test",
                LoggerFactory.getLogger("claims-test"));
        db.migrate(ClaimRepositoryIT.class.getClassLoader(), "core");
        db.migrate(ClaimRepositoryIT.class.getClassLoader(), "claims");
    }

    @AfterAll
    static void stop() {
        if (db != null) {
            db.close();
        }
        if (mariadb != null) {
            mariadb.stop();
        }
    }

    @BeforeEach
    void clean() throws Exception {
        db.withConnection(c -> {
            try (Statement st = c.createStatement()) {
                st.execute("SET FOREIGN_KEY_CHECKS = 0");
                for (String t : List.of("town_project_contributions", "town_projects", "town_vaults", "town_plots",
                        "town_roles", "town_members", "towns", "claims", "claim_trust", "claim_denied", "claim_players",
                        "players")) {
                    st.execute("TRUNCATE TABLE " + t);
                }
                st.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
            return null;
        });
        repo = new ClaimRepository(db);
    }

    private static Claim claim(int x, int z, Owner owner) {
        return new Claim(new ChunkKey(W, x, z), owner, Flag.MOB_SPAWNING.bit, owner.id(),
                Instant.now().truncatedTo(ChronoUnit.MILLIS));
    }

    private Town town(String name, String tag, UUID mayor, ChunkKey center) {
        return new Town(UUID.randomUUID(), name, tag, TagColor.AQUA, mayor, center, Optional.empty(),
                Instant.now().plus(Duration.ofDays(1)).truncatedTo(ChronoUnit.MILLIS), Town.State.ACTIVE,
                Optional.empty(), Map.of(mayor, TownRole.MAYOR), Town.defaultRoles(), Set.of());
    }

    @Test
    void claimsRoundTripAndAChunkIsClaimedOnce() throws Exception {
        Claim c = claim(3, -4, Owner.player(steve));
        assertTrue(repo.insertClaim(c));
        assertFalse(repo.insertClaim(claim(3, -4, Owner.player(alex))), "first claim wins, on any server");
        assertEquals(Optional.of(c), repo.claim(c.chunk()));

        repo.setFlags(List.of(c.chunk()), Flag.PVP.bit);
        assertEquals(Flag.PVP.bit, repo.claim(c.chunk()).orElseThrow().flags());

        assertFalse(repo.deleteClaim(c.chunk(), Owner.player(alex)), "only the owner it's checked against");
        assertTrue(repo.deleteClaim(c.chunk(), Owner.player(steve)));
        assertTrue(repo.claim(c.chunk()).isEmpty());
    }

    @Test
    void trustAndDenials() throws Exception {
        Owner o = Owner.player(steve);
        repo.setTrust(o, alex, Role.BUILDER);
        repo.setTrust(o, Access.EVERYONE, Role.TRUSTED);
        repo.setTrust(o, alex, Role.MEMBER);
        UUID griefer = UUID.randomUUID();
        repo.setDenied(o, griefer, true);
        repo.setDenied(o, griefer, true);
        Access a = repo.access(o);
        assertEquals(Map.of(alex, Role.MEMBER, Access.EVERYONE, Role.TRUSTED), a.trust());
        assertEquals(Set.of(griefer), a.denied());
        repo.setTrust(o, alex, null);
        repo.setDenied(o, griefer, false);
        assertEquals(Access.NONE.withTrust(Access.EVERYONE, Role.TRUSTED), repo.access(o));
    }

    @Test
    void allowanceAndPersonalRuins() throws Exception {
        assertEquals(ClaimRepository.PlayerRow.empty(steve), repo.player(steve));
        repo.addAllowance(steve, 2, 0);
        ClaimRepository.PlayerRow row = repo.addAllowance(steve, 1, 5);
        assertEquals(3, row.purchased());
        assertEquals(5, row.bonus());

        Instant since = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        repo.setPlayerRuins(steve, since);
        assertEquals(Optional.of(since), repo.player(steve).ruinsSince());
        repo.setPlayerRuins(steve, null);
        assertTrue(repo.player(steve).ruinsSince().isEmpty());
    }

    @Test
    void ownersAwayLongAreFound() throws Exception {
        db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO players (uuid, name, last_join) VALUES (?, ?, ?)")) {
                ps.setString(1, steve.toString());
                ps.setString(2, "Steve");
                ps.setObject(3, ClaimRepository.ts(Instant.now().minus(Duration.ofDays(100))));
                ps.executeUpdate();
                ps.setString(1, alex.toString());
                ps.setString(2, "Alex");
                ps.setObject(3, ClaimRepository.ts(Instant.now()));
                ps.executeUpdate();
            }
            return null;
        });
        repo.insertClaim(claim(0, 0, Owner.player(steve)));
        repo.insertClaim(claim(1, 0, Owner.player(alex)));
        assertEquals(Set.of(steve), repo.personalOwnersAwaySince(Instant.now().minus(Duration.ofDays(90))));
        assertEquals(Map.of(steve, "Steve", alex, "Alex"), repo.names(List.of(steve, alex, UUID.randomUUID())));
    }

    @Test
    void townsFromFoundingToDeletion() throws Exception {
        ChunkKey center = new ChunkKey(W, 0, 0);
        repo.insertClaim(claim(0, 0, Owner.player(steve)));
        Town t = town("Riverside", "RIV", steve, center);
        assertTrue(repo.createTown(t, claim(0, 0, Owner.town(t.id())), true), "the mayor's own chunk becomes the center");
        assertEquals(Owner.town(t.id()), repo.claim(center).orElseThrow().owner());
        assertFalse(repo.createTown(town("riverside", "XYZ", alex, new ChunkKey(W, 9, 9)),
                claim(9, 9, Owner.town(UUID.randomUUID())), false), "names are unique ignoring case");
        assertTrue(repo.claim(new ChunkKey(W, 9, 9)).isEmpty(), "a failed founding leaves nothing behind");

        assertTrue(repo.addMember(t.id(), alex, TownRole.RECRUIT));
        assertFalse(repo.addMember(t.id(), alex, TownRole.RECRUIT), "one town per player");
        repo.setMemberRole(t.id(), alex, TownRole.ASSISTANT);
        repo.setRole(t.id(), TownRole.RECRUIT, new Town.RoleSettings(EnumSet.of(TownPermission.INVITE), Role.MEMBER));
        Claim second = claim(1, 0, Owner.town(t.id()));
        repo.insertClaim(second);
        repo.setPlot(new Plot(second.chunk(), t.id(), alex));
        repo.startProject(t.id(), "lighthouse");
        repo.contribute(t.id(), "lighthouse", alex, "crowns", 10);

        Town loaded = repo.town(t.id()).orElseThrow();
        assertEquals(Map.of(steve, TownRole.MAYOR, alex, TownRole.ASSISTANT), loaded.members());
        assertEquals(Set.of(TownPermission.INVITE), loaded.roles().get(TownRole.RECRUIT).permissions());
        assertEquals(Role.MEMBER, loaded.roles().get(TownRole.RECRUIT).land());
        assertEquals(Town.defaultRoles().get(TownRole.RESIDENT), loaded.roles().get(TownRole.RESIDENT));
        assertEquals(Optional.of(new Plot(second.chunk(), t.id(), alex)), repo.plot(second.chunk()));

        repo.setMayor(t.id(), steve, alex);
        assertEquals(alex, repo.town(t.id()).orElseThrow().mayor());
        assertEquals(TownRole.ASSISTANT, repo.town(t.id()).orElseThrow().members().get(steve));

        Town renamed = loaded.withName("Hillside").withTag("HIL", TagColor.GREEN);
        assertTrue(repo.updateTownIdentity(renamed));
        repo.updateWarp(t.id(), Optional.of("minecraft:overworld, 1, 64, 1, 0, 0"));
        Town ruined = renamed.withUpkeep(Instant.now().truncatedTo(ChronoUnit.MILLIS), Town.State.RUINS,
                Optional.of(Instant.now().truncatedTo(ChronoUnit.MILLIS)));
        repo.updateUpkeep(ruined);
        Town again = repo.town(t.id()).orElseThrow();
        assertEquals("Hillside", again.name());
        assertEquals(TagColor.GREEN, again.tagColor());
        assertTrue(again.ruined());
        assertEquals(ruined.ruinsSince(), again.ruinsSince());
        assertEquals(Optional.of("minecraft:overworld, 1, 64, 1, 0, 0"), again.warp());

        repo.removeMember(t.id(), steve);
        assertFalse(repo.town(t.id()).orElseThrow().isMember(steve));

        assertEquals(2, repo.deleteTown(t.id()), "both chunks released");
        assertTrue(repo.town(t.id()).isEmpty());
        assertTrue(repo.plot(second.chunk()).isEmpty(), "plots go with the town");
        assertTrue(repo.claim(center).isEmpty());
    }

    @Test
    void leavingATownHandsPlotsBack() throws Exception {
        Town t = town("Oakvale", "OAK", steve, new ChunkKey(W, 0, 0));
        repo.createTown(t, claim(0, 0, Owner.town(t.id())), false);
        repo.addMember(t.id(), alex, TownRole.RESIDENT);
        repo.setPlot(new Plot(new ChunkKey(W, 0, 0), t.id(), alex));
        repo.removeMember(t.id(), alex);
        assertTrue(repo.plot(new ChunkKey(W, 0, 0)).isEmpty());
    }

    @Test
    void releasingAllOfAnOwnersLandDropsTheirPlots() throws Exception {
        Town t = town("Pinecrest", "PIN", steve, new ChunkKey(W, 0, 0));
        repo.createTown(t, claim(0, 0, Owner.town(t.id())), false);
        repo.insertClaim(claim(0, 1, Owner.town(t.id())));
        repo.setPlot(new Plot(new ChunkKey(W, 0, 1), t.id(), steve));
        repo.insertClaim(claim(5, 5, Owner.player(alex)));
        assertEquals(2, repo.deleteAllClaims(Owner.town(t.id())));
        assertTrue(repo.plot(new ChunkKey(W, 0, 1)).isEmpty());
        assertTrue(repo.claim(new ChunkKey(W, 5, 5)).isPresent(), "nobody else's land");

        repo.setOwner(List.of(new ChunkKey(W, 5, 5)), Owner.player(steve));
        assertEquals(Owner.player(steve), repo.claim(new ChunkKey(W, 5, 5)).orElseThrow().owner());
    }

    @Test
    void projectsAndTheVault() throws Exception {
        Town t = town("Lakeside", "LAK", steve, new ChunkKey(W, 0, 0));
        repo.createTown(t, claim(0, 0, Owner.town(t.id())), false);
        assertTrue(repo.startProject(t.id(), "lighthouse"));
        assertFalse(repo.startProject(t.id(), "lighthouse"));
        repo.contribute(t.id(), "lighthouse", steve, "minecraft:glass", 40);
        repo.contribute(t.id(), "lighthouse", steve, "minecraft:glass", 24);
        repo.contribute(t.id(), "lighthouse", alex, "crowns", 5000);

        ClaimRepository.ProjectRow row = repo.projects(t.id()).getFirst();
        assertFalse(row.completed());
        assertEquals(Map.of("minecraft:glass", 64L, "crowns", 5000L), row.totals());
        assertEquals(Map.of(steve, 64L, alex, 5000L), row.contributors());

        assertTrue(repo.completeProject(t.id(), "lighthouse"));
        assertFalse(repo.completeProject(t.id(), "lighthouse"), "completes once");
        assertEquals(Set.of("lighthouse"), repo.town(t.id()).orElseThrow().completed());

        assertTrue(repo.vault(t.id()).isEmpty());
        byte[] contents = {1, 2, 3, 4};
        repo.saveVault(t.id(), contents);
        repo.saveVault(t.id(), contents);
        assertArrayEquals(contents, repo.vault(t.id()).orElseThrow());
    }

    @Test
    void theStartupSnapshot() throws Exception {
        repo.insertClaim(claim(0, 0, Owner.player(steve)));
        repo.insertClaim(new Claim(new ChunkKey("minecraft:the_nether", 0, 0), Owner.player(steve), 0, steve,
                Instant.now()));
        repo.setTrust(Owner.player(steve), alex, Role.BUILDER);
        Town t = town("Brookfield", "BRK", alex, new ChunkKey(W, 4, 4));
        repo.createTown(t, claim(4, 4, Owner.town(t.id())), false);
        repo.setPlot(new Plot(new ChunkKey(W, 4, 4), t.id(), alex));
        repo.addAllowance(steve, 1, 0);

        ClaimRepository.Snapshot s = repo.load(Set.of(W));
        assertEquals(2, s.claims().size(), "only claimable worlds");
        assertEquals(Role.BUILDER, s.access().get(Owner.player(steve)).trust().get(alex));
        assertEquals(1, s.towns().size());
        assertEquals(1, s.plots().size());
        assertEquals(1, s.players().get(steve).purchased());
    }
}
