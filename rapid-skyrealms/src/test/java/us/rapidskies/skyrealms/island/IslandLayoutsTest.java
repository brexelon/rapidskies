package us.rapidskies.skyrealms.island;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class IslandLayoutsTest {

    @Test
    void everyLayoutIsAFairStart() {
        java.util.Map<String, Integer> usable = new java.util.HashMap<>();
        for (String id : IslandLayouts.IDS) {
            IslandLayouts.Layout l = IslandLayouts.build(id);
            Blueprint b = l.blueprint();
            assertTrue(b.isAir(0, 0, 0) && b.isAir(0, 1, 0), id + ": room to stand at the spawn");
            assertEquals(Material.GRASS_BLOCK, b.material(0, -1, 0), id + ": grass under the spawn");
            assertEquals(Material.CHEST, b.material(l.chestX(), l.chestY(), l.chestZ()), id + ": the starter chest");
            assertTrue(b.isAir(l.chestX(), l.chestY() + 1, l.chestZ()), id + ": the chest opens");
            Map<Material, Integer> counts = b.counts();
            assertTrue(counts.keySet().stream().anyMatch(m -> m.name().endsWith("_LOG")), id + ": a tree");
            assertTrue(counts.getOrDefault(Material.SAND, 0) >= 3, id + ": sand for cactus and sugar cane");
            assertTrue(counts.getOrDefault(Material.COAL_ORE, 0) >= 2, id + ": coal for torches");
            int soil = counts.getOrDefault(Material.DIRT, 0) + counts.getOrDefault(Material.GRASS_BLOCK, 0);
            assertTrue(soil >= 100, id + ": enough soil to farm (" + soil + ")");
            usable.put(id, soil + counts.getOrDefault(Material.SAND, 0));
            assertTrue(l.sapling().name().endsWith("_SAPLING"));
        }
        int most = usable.values().stream().mapToInt(Integer::intValue).max().orElseThrow();
        usable.forEach((id, n) -> assertTrue(n >= most * 0.8, id + ": about as much dirt and sand as the others "
                + usable));
    }

    @Test
    void leavesNeverDecay() {
        for (String id : IslandLayouts.IDS) {
            for (Blueprint.Block blk : IslandLayouts.build(id).blueprint().blocks()) {
                if (blk.material().name().endsWith("_LEAVES")) {
                    assertEquals("[persistent=true]", blk.state(), id + " " + blk);
                }
            }
        }
    }

    @Test
    void islandsHangDeep() {
        for (String id : IslandLayouts.IDS) {
            int[] bounds = IslandLayouts.build(id).blueprint().bounds();
            assertTrue(bounds[1] <= -9, id + ": the rock under it reaches y " + bounds[1]);
            assertTrue(bounds[3] - bounds[0] <= 16 && bounds[5] - bounds[2] <= 16, id + ": fits well inside the island");
        }
    }

    /** Drawn from a fixed seed: every paste (and every test run) is the same island. */
    @Test
    void sameIslandEveryTime() {
        for (String id : IslandLayouts.IDS) {
            assertEquals(List.copyOf(IslandLayouts.build(id).blueprint().blocks()),
                    List.copyOf(IslandLayouts.build(id).blueprint().blocks()), id);
        }
    }

    /**
     * Blocks are pasted without physics, so anything that needs support has to have it, or the
     * first block update next to it drops it into the void or pops it off.
     */
    @Test
    void everythingIsHeldUp() {
        Set<Material> soil = Set.of(Material.GRASS_BLOCK, Material.DIRT);
        Set<Material> plants = Set.of(Material.SHORT_GRASS, Material.FERN, Material.DANDELION, Material.POPPY,
                Material.OXEYE_DAISY, Material.AZURE_BLUET, Material.CORNFLOWER, Material.ALLIUM,
                Material.LILY_OF_THE_VALLEY, Material.PINK_PETALS);
        // Material.isSolid() needs a server (Registry), so: air and everything the layouts hang or plant.
        Set<Material> flimsy = new java.util.HashSet<>(plants);
        flimsy.addAll(Set.of(Material.AIR, Material.TALL_GRASS, Material.LARGE_FERN, Material.DEAD_BUSH,
                Material.HANGING_ROOTS, Material.GLOW_LICHEN, Material.VINE, Material.MOSS_CARPET));
        java.util.function.Predicate<Material> solid = m -> !flimsy.contains(m);
        for (String id : IslandLayouts.IDS) {
            Blueprint b = IslandLayouts.build(id).blueprint();
            for (Blueprint.Block blk : b.blocks()) {
                Material m = blk.material();
                Material below = b.material(blk.x(), blk.y() - 1, blk.z());
                Material above = b.material(blk.x(), blk.y() + 1, blk.z());
                String where = id + " " + blk;
                if (IslandLayouts.FALLING.contains(m)) {
                    assertTrue(solid.test(below), where + " rests on " + below);
                } else if (plants.contains(m)) {
                    assertTrue(soil.contains(below), where + " grows on " + below);
                } else if (m == Material.DEAD_BUSH) {
                    assertEquals(Material.SAND, below, where);
                } else if (m == Material.TALL_GRASS || m == Material.LARGE_FERN) {
                    boolean lower = blk.state().equals("[half=lower]");
                    assertTrue(lower ? soil.contains(below) && above == m : below == m, where + ": both halves");
                } else if (m == Material.HANGING_ROOTS || m == Material.GLOW_LICHEN) {
                    assertTrue(solid.test(above), where + " hangs from " + above);
                } else if (m == Material.MOSS_CARPET) {
                    assertTrue(solid.test(below), where);
                }
            }
        }
    }

    @Test
    void unknownLayoutsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> IslandLayouts.build("castle"));
    }

    /** SuperiorSkyblock2 gunzips every schematic file before a parser reads it; ours must survive that. */
    @Test
    void markersReadTheWaySuperiorSkyblockReadsThem() throws Exception {
        byte[] file = IslandSchematics.marker("cherry");
        try (java.io.DataInputStream in = new java.io.DataInputStream(
                new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(file)))) {
            assertEquals("cherry", IslandSchematics.markerId(in.readNBytes(64)));
        }
        assertEquals("cherry", IslandSchematics.markerId(IslandSchematics.unpack(file)));
        // The plain-text markers of the first deploy are still recognised (and rewritten).
        assertEquals("cherry", IslandSchematics.markerId(IslandSchematics.unpack(
                (IslandSchematics.MAGIC + "cherry\n").getBytes(StandardCharsets.US_ASCII))));
        // A real (gzipped NBT) schematic isn't ours.
        java.io.ByteArrayOutputStream nbt = new java.io.ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(nbt)) {
            gz.write(new byte[] {10, 0, 0, 3, 0, 5});
        }
        assertNull(IslandSchematics.markerId(IslandSchematics.unpack(nbt.toByteArray())));
    }

    @Test
    void markersAreRecognised() {
        assertEquals("meadow", IslandSchematics.markerId((IslandSchematics.MAGIC + "meadow\n")
                .getBytes(StandardCharsets.US_ASCII)));
        // SuperiorSkyblock2's own schematics are gzip (1f 8b): not ours.
        assertNull(IslandSchematics.markerId(new byte[] {0x1f, (byte) 0x8b, 8, 0}));
        assertNull(IslandSchematics.markerId(IslandSchematics.MAGIC.getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void displayNames() {
        assertEquals("Steve's Island", Islands.displayName("", "Steve"));
        assertEquals("Sky Farm", Islands.displayName("&6Sky &lFarm", "Steve"));
        assertEquals("Alex's Island", Islands.displayName(null, "Alex"));
    }
}
