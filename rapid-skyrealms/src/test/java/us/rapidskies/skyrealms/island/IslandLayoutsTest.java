package us.rapidskies.skyrealms.island;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Map;
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
    void unknownLayoutsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> IslandLayouts.build("castle"));
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
