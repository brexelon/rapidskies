package us.rapidskies.horizons.skyborne;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import us.rapidskies.horizons.skyborne.FortressLayout.Placement;

/** The fortress design and how the fight scales. */
class SkyborneRulesTest {

    /** Everything the fortress is built from: all in 1.20 (PLAN.md §2: gameplay blocks exist in 1.20). */
    private static final Set<String> BLOCKS_1_20 = Set.of("GRASS_BLOCK", "COARSE_DIRT", "DIRT", "ROOTED_DIRT",
            "MOSS_BLOCK", "MOSS_CARPET", "MOSSY_COBBLESTONE", "STONE", "ANDESITE", "TUFF", "COBBLESTONE", "GOLD_ORE",
            "BLACKSTONE", "BASALT", "GILDED_BLACKSTONE", "POINTED_DRIPSTONE", "HANGING_ROOTS",
            "CHISELED_POLISHED_BLACKSTONE", "POLISHED_BLACKSTONE", "POLISHED_BLACKSTONE_BRICKS",
            "CRACKED_POLISHED_BLACKSTONE_BRICKS", "POLISHED_BLACKSTONE_BRICK_STAIRS", "POLISHED_BLACKSTONE_BRICK_WALL",
            "GOLD_BLOCK", "SEA_LANTERN", "SHROOMLIGHT", "LANTERN", "CAMPFIRE", "END_ROD", "YELLOW_STAINED_GLASS",
            "TINTED_GLASS", "YELLOW_WALL_BANNER", "QUARTZ_PILLAR", "SMOOTH_QUARTZ", "CHISELED_QUARTZ_BLOCK",
            "DARK_OAK_LOG", "AZALEA_LEAVES", "FLOWERING_AZALEA_LEAVES", "AZALEA", "FLOWERING_AZALEA", "DANDELION",
            "OXEYE_DAISY", "AZURE_BLUET", "FERN");

    /** Block states as Bukkit parses them, without the brackets. */
    private static final Pattern STATE = Pattern.compile("[a-z_]+=[a-z0-9_]+(,[a-z_]+=[a-z0-9_]+)*");

    private final List<Placement> layout = FortressLayout.blocks();

    @Test
    void theLayoutIsStableAndUnique() {
        assertEquals(layout, FortressLayout.blocks(), "the same every time, so clearing matches building");
        Set<List<Integer>> positions = new HashSet<>();
        for (Placement p : layout) {
            assertTrue(positions.add(List.of(p.dx(), p.dy(), p.dz())), "placed twice: " + p);
            assertTrue(FortressLayout.inside(p.dx(), p.dy(), p.dz()), "outside the protected box: " + p);
            assertTrue(BLOCKS_1_20.contains(p.material()), p.material());
            assertNotNull(Material.matchMaterial(p.material()), "a Material on this server: " + p.material());
            assertTrue(p.state().isEmpty() || STATE.matcher(p.state()).matches(), "block states: " + p);
        }
        assertTrue(layout.size() > 3000 && layout.size() < 20000, "a real island, built in a few ticks: "
                + layout.size());
    }

    private Placement get(int x, int y, int z) {
        return layout.stream().filter(p -> p.dx() == x && p.dy() == y && p.dz() == z).findFirst().orElse(null);
    }

    private boolean at(int x, int y, int z) {
        return get(x, y, z) != null;
    }

    @Test
    void nothingChangesOnItsOwn() {
        // What clearing removes must still be what was built.
        for (Placement p : layout) {
            Placement above = get(p.dx(), p.dy() + 1, p.dz());
            if (p.material().equals("GRASS_BLOCK") && above != null) {
                assertTrue(Set.of("DANDELION", "OXEYE_DAISY", "AZURE_BLUET", "FERN", "AZALEA", "FLOWERING_AZALEA")
                        .contains(above.material()), "grass would turn to dirt under " + above);
            }
            if (p.material().endsWith("_LEAVES")) {
                assertTrue(p.state().contains("persistent=true"), "leaves would decay: " + p);
            }
        }
    }

    @Test
    void hangingThingsHaveSomethingToHangFrom() {
        for (Placement p : layout) {
            boolean hangs = p.state().contains("hanging=true") || p.material().equals("HANGING_ROOTS")
                    || p.material().equals("POINTED_DRIPSTONE")
                    || p.material().equals("POLISHED_BLACKSTONE_BRICK_WALL") && !at(p.dx(), p.dy() - 1, p.dz());
            if (hangs) {
                assertTrue(at(p.dx(), p.dy() + 1, p.dz()), "nothing above " + p);
            }
            if (p.material().equals("YELLOW_WALL_BANNER")) {
                int[] back = switch (p.state()) {
                    case "facing=north" -> new int[] {0, 1};
                    case "facing=south" -> new int[] {0, -1};
                    case "facing=east" -> new int[] {-1, 0};
                    default -> new int[] {1, 0};
                };
                assertTrue(at(p.dx() + back[0], p.dy(), p.dz() + back[1]), "banner on nothing: " + p);
            }
            if (Set.of("LANTERN", "CAMPFIRE", "FERN", "DANDELION", "AZALEA", "MOSS_CARPET").contains(p.material())
                    && p.state().isEmpty()) {
                assertTrue(at(p.dx(), p.dy() - 1, p.dz()), "standing on nothing: " + p);
            }
        }
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
        assertTrue(at(0, 0, FortressLayout.PAD_Z + FortressLayout.PAD_HALF), "the pad's south rim");
        assertTrue(FortressLayout.UPDRAFT_Z > FortressLayout.PAD_Z + FortressLayout.PAD_HALF,
                "the column is just past the pad's rim");
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
