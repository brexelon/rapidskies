package us.rapidskies.skyrealms.expedition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import us.rapidskies.skyrealms.island.Blueprint;

class ExpeditionLayoutsTest {

    private static final long[] SEEDS = {1, 42, -7, 123456789L, Long.MIN_VALUE};

    @Test
    void everyThemeHasItsHeartAndPad() {
        for (Theme theme : Theme.values()) {
            for (long seed : SEEDS) {
                ExpeditionLayouts.Layout l = ExpeditionLayouts.build(theme, seed);
                Blueprint b = l.blueprint();
                String what = theme.id() + " seed " + seed;
                assertEquals(theme.heart(), b.material(l.heartX(), l.heartY(), l.heartZ()), what + ": heart");
                assertTrue(b.exposed(l.heartX(), l.heartY(), l.heartZ()), what + ": the heart can be struck");
                int px = (int) Math.floor(l.padX());
                int py = (int) Math.floor(l.padY());
                int pz = (int) Math.floor(l.padZ());
                assertFalse(b.isAir(px, py - 1, pz), what + ": ground under the pad");
                assertTrue(b.isAir(px, py, pz) && b.isAir(px, py + 1, pz), what + ": room to land");
            }
        }
    }

    @Test
    void resourceBlocksAreAllReachable() {
        for (Theme theme : Theme.values()) {
            for (long seed : SEEDS) {
                ExpeditionLayouts.Layout l = ExpeditionLayouts.build(theme, seed);
                Blueprint b = l.blueprint();
                Set<Material> seen = EnumSet.noneOf(Material.class);
                int nodes = 0;
                for (Blueprint.Block blk : b.blocks()) {
                    if (!l.node(blk)) {
                        continue;
                    }
                    nodes++;
                    seen.add(blk.material());
                    // Only resource blocks can be broken, so each must face air, or sit under a
                    // resource block (reachable once that's mined).
                    boolean underNode = theme.nodeMaterials().contains(b.material(blk.x(), blk.y() + 1, blk.z()));
                    assertTrue(b.exposed(blk.x(), blk.y(), blk.z()) || underNode,
                            theme.id() + " seed " + seed + ": " + blk + " can't be reached");
                }
                assertTrue(nodes >= 25, theme.id() + " seed " + seed + ": only " + nodes + " resource blocks");
                assertTrue(seen.size() >= theme.nodeMaterials().size() - 1,
                        theme.id() + " seed " + seed + ": most resource kinds appear, got " + seen);
            }
        }
    }

    @Test
    void nothingFallsIntoTheVoid() {
        for (Theme theme : Theme.values()) {
            Blueprint b = ExpeditionLayouts.build(theme, 99).blueprint();
            for (Blueprint.Block blk : b.blocks()) {
                if (blk.material() == Material.SAND || blk.material() == Material.GRAVEL) {
                    assertFalse(b.isAir(blk.x(), blk.y() - 1, blk.z()), theme.id() + ": " + blk + " would fall");
                }
            }
        }
    }

    @Test
    void sameSeedSameIsland() {
        for (Theme theme : Theme.values()) {
            Map<Material, Integer> a = ExpeditionLayouts.build(theme, 5).blueprint().counts();
            assertEquals(a, ExpeditionLayouts.build(theme, 5).blueprint().counts(), theme.id());
        }
    }

    @Test
    void heartLootFollowsHitsNotTheLastOne() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        Map<UUID, Integer> hits = new HashMap<>(Map.of(a, 10, b, 10, c, 10));
        assertEquals(4, Shares.heart(4, 10, hits), "an average striker gets the listed amount");
        hits = new HashMap<>(Map.of(a, 30, b, 10, c, 2));
        assertEquals(8, Shares.heart(4, 30, hits), "never more than double");
        assertEquals(3, Shares.heart(4, 10, hits));
        assertEquals(1, Shares.heart(4, 2, hits));
        assertEquals(0, Shares.heart(4, 0, hits));
        assertEquals(1, Shares.heart(1, 1, Map.of(a, 1)), "a lone striker gets it all");
    }

    @Test
    void hitsAndCoins() {
        assertEquals(24, Shares.hitsNeeded(12, 24, 1));
        assertEquals(60, Shares.hitsNeeded(12, 24, 5));
        assertEquals(60, Shares.coins(10, 6, 1.0));
        assertEquals(90, Shares.coins(10, 6, 1.5), "Tailwind");
        assertEquals(60, Shares.coins(10, 6, 0.5), "never below 1x");
    }
}
