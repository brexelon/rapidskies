package us.rapidskies.horizons.skyborne;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import us.rapidskies.horizons.skyborne.FortressLayout.Placement;

/** The fortress design and how the fight scales. */
class SkyborneRulesTest {

    /** Everything the fortress is built from: all in 1.20 (PLAN.md §2: gameplay blocks exist in 1.20). */
    private static final Set<String> BLOCKS_1_20 = Set.of("GRASS_BLOCK", "COARSE_DIRT", "DIRT", "STONE", "ANDESITE",
            "COBBLESTONE", "BLACKSTONE", "COAL_BLOCK", "CHISELED_POLISHED_BLACKSTONE", "GOLD_BLOCK", "SEA_LANTERN",
            "GILDED_BLACKSTONE", "POLISHED_BLACKSTONE_BRICKS", "POLISHED_BLACKSTONE", "YELLOW_STAINED_GLASS",
            "QUARTZ_BLOCK");

    private final List<Placement> layout = FortressLayout.blocks();

    @Test
    void theLayoutIsStableAndUnique() {
        assertEquals(layout, FortressLayout.blocks(), "the same every time, so clearing matches building");
        Set<List<Integer>> positions = new HashSet<>();
        for (Placement p : layout) {
            assertTrue(positions.add(List.of(p.dx(), p.dy(), p.dz())), "placed twice: " + p);
            assertTrue(FortressLayout.inside(p.dx(), p.dy(), p.dz()), "outside the protected box: " + p);
            assertTrue(BLOCKS_1_20.contains(p.material()), p.material());
        }
        assertTrue(layout.size() > 3000 && layout.size() < 20000, "a real island, built in a few ticks: "
                + layout.size());
    }

    private boolean at(int x, int y, int z) {
        return layout.stream().anyMatch(p -> p.dx() == x && p.dy() == y && p.dz() == z);
    }

    @Test
    void gatesAreOpenAndWallsStand() {
        int w = FortressLayout.WALL;
        for (int y = 1; y <= 3; y++) {
            assertFalse(at(0, y, w), "south gate");
            assertFalse(at(0, y, -w), "north gate");
            assertFalse(at(w, y, 0), "east gate");
            assertFalse(at(-w, y, 0), "west gate");
        }
        assertTrue(at(5, 2, w), "wall beside the gate");
        assertTrue(at(w, FortressLayout.TOWER_HEIGHT - 1, w), "a corner tower");
        assertTrue(at(0, 0, 0), "the dais");
    }

    @Test
    void theUpdraftLeadsToThePad() {
        for (int y = FortressLayout.MIN_Y; y <= FortressLayout.MAX_Y; y++) {
            assertFalse(at(FortressLayout.UPDRAFT_X, y, FortressLayout.UPDRAFT_Z), "the updraft column is clear");
        }
        assertTrue(at(0, 0, FortressLayout.PAD_Z), "the landing pad");
        assertTrue(at(0, 0, FortressLayout.WALL + 2), "the path to the south gate");
        assertTrue(FortressLayout.UPDRAFT_Z > FortressLayout.PAD_Z + 2, "the column is just past the pad's rim");
    }

    @Test
    void mobsSpawnOnTheCourtyardFloor() {
        for (int[] s : FortressLayout.spawnSpots()) {
            assertTrue(at(s[0], 0, s[2]), "floor under a spawn spot");
            assertFalse(at(s[0], 1, s[2]), "room to stand");
            assertTrue(Math.max(Math.abs(s[0]), Math.abs(s[2])) < FortressLayout.WALL, "inside the walls");
        }
    }

    private final SkyborneScaling scaling = new SkyborneScaling(6, 2, 2, 40, 300, 35);

    @Test
    void wavesGrowWithPlayersAndWaves() {
        assertEquals(6, scaling.waveSize(1, 1));
        assertEquals(6, scaling.waveSize(1, 0), "never fewer than one player's worth");
        assertEquals(14, scaling.waveSize(1, 5));
        assertEquals(10, scaling.waveSize(3, 1));
        assertEquals(40, scaling.waveSize(3, 50), "capped");
    }

    @Test
    void theBossScalesAndIsCapped() {
        assertEquals(300, scaling.bossHealth(1));
        assertEquals(300 * (1 + 0.35 * 3), scaling.bossHealth(4), 1e-9);
        assertEquals(SkyborneScaling.HEALTH_CAP, scaling.bossHealth(100), "Minecraft's max health");
    }

    @Test
    void theNextFortressIsSpreadOut() {
        Instant now = Instant.parse("2026-11-10T12:00:00Z");
        Random random = new Random(1);
        for (int i = 0; i < 200; i++) {
            Instant next = SkyborneScaling.next(now, Duration.ofHours(4), Duration.ofMinutes(30), random);
            assertTrue(!next.isBefore(now.plus(Duration.ofMinutes(210))) && !next.isAfter(now.plus(Duration.ofMinutes(270))),
                    next.toString());
        }
        assertEquals(now.plus(Duration.ofHours(4)),
                SkyborneScaling.next(now, Duration.ofHours(4), Duration.ZERO, random), "no jitter");
    }
}
