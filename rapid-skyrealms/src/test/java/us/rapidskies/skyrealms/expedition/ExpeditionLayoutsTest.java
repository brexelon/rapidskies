package us.rapidskies.skyrealms.expedition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import us.rapidskies.skyrealms.island.Blueprint;

class ExpeditionLayoutsTest {

    private static final long[] SEEDS = {1, 42, -7, 123456789L, Long.MIN_VALUE};
    /** Many more for walking round the island: a pit only some seeds dig is still a pit. */
    private static final long[] MANY = java.util.stream.LongStream.concat(java.util.Arrays.stream(SEEDS),
            new java.util.Random(7).longs(60)).toArray();

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
    void theHeartIsReachedOnFootFromThePad() {
        for (Theme theme : Theme.values()) {
            for (long seed : MANY) {
                ExpeditionLayouts.Layout l = ExpeditionLayouts.build(theme, seed);
                Walk walk = new Walk(l);
                boolean beside = walk.reached.stream().map(Walk::unpack).anyMatch(p ->
                        Math.max(Math.abs(p[0] - l.heartX()), Math.abs(p[2] - l.heartZ())) == 1
                                && Math.abs(p[1] - l.heartY()) <= 1);
                assertTrue(beside, theme.id() + " seed " + seed + ": nobody can walk up to the heart");
            }
        }
    }

    @Test
    void nobodyGetsStuck() {
        for (Theme theme : Theme.values()) {
            for (long seed : MANY) {
                Walk walk = new Walk(ExpeditionLayouts.build(theme, seed));
                Set<Long> back = walk.canReturn();
                for (long p : walk.reached) {
                    assertTrue(back.contains(p), theme.id() + " seed " + seed + ": stuck at "
                            + Arrays.toString(Walk.unpack(p)));
                }
            }
        }
    }

    @Test
    void everyResourceBlockIsWithinReachOfSomewhereToStand() {
        for (Theme theme : Theme.values()) {
            for (long seed : MANY) {
                ExpeditionLayouts.Layout l = ExpeditionLayouts.build(theme, seed);
                List<int[]> stands = new Walk(l).reached.stream().map(Walk::unpack).toList();
                for (Blueprint.Block blk : l.blueprint().blocks()) {
                    if (!l.node(blk)) {
                        continue;
                    }
                    // Survival reach is 4.5 blocks from the eyes (1.62 above the feet).
                    boolean near = stands.stream().anyMatch(p -> {
                        double dx = p[0] - blk.x();
                        double dy = p[1] + 1.62 - (blk.y() + 0.5);
                        double dz = p[2] - blk.z();
                        return dx * dx + dy * dy + dz * dz <= 4.5 * 4.5;
                    });
                    assertTrue(near, theme.id() + " seed " + seed + ": " + blk + " is out of reach");
                }
            }
        }
    }

    @Test
    void theShipsDeckSterncastleAndHoldCanBeWalked() {
        for (long seed : MANY) {
            ExpeditionLayouts.Layout l = ExpeditionLayouts.build(Theme.SUNKEN_SHIP, seed);
            Blueprint b = l.blueprint();
            Set<Material> floors = EnumSet.noneOf(Material.class);
            for (long p : new Walk(l).reached) {
                int[] q = Walk.unpack(p);
                if (q[1] == 4 || q[1] == 7 || q[1] == 1) {
                    floors.add(b.material(q[0], q[1] - 1, q[2]));
                }
            }
            assertTrue(floors.contains(Material.OAK_PLANKS), "seed " + seed + ": the deck");
            assertTrue(floors.contains(Material.SPRUCE_PLANKS), "seed " + seed + ": the poop deck");
            assertTrue(floors.contains(Material.DARK_OAK_PLANKS), "seed " + seed + ": the hold");
        }
    }

    /**
     * Blocks are placed without physics, so anything that needs support has to have it, or the
     * first block update next to it drops it into the void or pops it off.
     */
    @Test
    void everythingIsHeldUp() {
        for (Theme theme : Theme.values()) {
            for (long seed : MANY) {
                Blueprint b = ExpeditionLayouts.build(theme, seed).blueprint();
                for (Blueprint.Block blk : b.blocks()) {
                    assertTrue(heldUp(b, blk), theme.id() + " seed " + seed + ": " + blk + " isn't held up");
                }
            }
        }
    }

    /**
     * Water sits in a basin: something under it and on every side, and none of it a resource
     * block, so mining never opens a way out (or a waterfall off the island).
     */
    @Test
    void waterStaysInItsBasin() {
        for (Theme theme : Theme.values()) {
            for (long seed : MANY) {
                ExpeditionLayouts.Layout l = ExpeditionLayouts.build(theme, seed);
                Blueprint b = l.blueprint();
                for (Blueprint.Block blk : b.blocks()) {
                    boolean wet = blk.material() == Material.WATER || blk.material() == Material.SEAGRASS
                            || blk.state().contains("waterlogged=true");
                    if (!wet) {
                        continue;
                    }
                    for (int[] d : new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, -1, 0}}) {
                        Material next = b.material(blk.x() + d[0], blk.y() + d[1], blk.z() + d[2]);
                        assertTrue(next != Material.AIR && !theme.nodeMaterials().contains(next),
                                theme.id() + " seed " + seed + ": " + blk + " could run out past " + next);
                    }
                }
            }
        }
    }

    private static final Set<Material> SOIL = EnumSet.of(Material.GRASS_BLOCK, Material.DIRT);
    private static final Set<Material> PLANTS = EnumSet.of(Material.SHORT_GRASS, Material.FERN, Material.POPPY,
            Material.DANDELION, Material.OXEYE_DAISY, Material.AZURE_BLUET, Material.CORNFLOWER);

    private static boolean heldUp(Blueprint b, Blueprint.Block blk) {
        Material m = blk.material();
        String n = m.name();
        String st = blk.state();
        int x = blk.x();
        int y = blk.y();
        int z = blk.z();
        Material below = b.material(x, y - 1, z);
        Material above = b.material(x, y + 1, z);
        if (m == Material.POINTED_DRIPSTONE) {
            return st.contains("vertical_direction=down") ? above != Material.AIR : below != Material.AIR;
        }
        if (n.endsWith("AMETHYST_BUD") || m == Material.AMETHYST_CLUSTER || m == Material.LADDER) {
            // Held by the block behind it, opposite the way it faces.
            int[] d = direction(st.replaceAll(".*facing=(\\w+).*", "$1"));
            return b.material(x - d[0], y - d[1], z - d[2]) != Material.AIR;
        }
        if (m == Material.LANTERN) {
            return st.contains("hanging=true") ? above != Material.AIR : below != Material.AIR;
        }
        if (m == Material.VINE || m == Material.GLOW_LICHEN) {
            boolean any = false;
            for (String face : List.of("north", "east", "south", "west", "up", "down")) {
                if (st.contains(face + "=true")) {
                    int[] d = direction(face);
                    boolean held = b.material(x + d[0], y + d[1], z + d[2]) != Material.AIR
                            || m == Material.VINE && above == Material.VINE;
                    if (!held) {
                        return false;
                    }
                    any = true;
                }
            }
            return any;
        }
        if (m == Material.LILY_PAD) {
            return below == Material.WATER;
        }
        if (m == Material.HANGING_ROOTS) {
            return above != Material.AIR;
        }
        if (m == Material.TALL_GRASS) {
            return st.contains("upper") ? below == Material.TALL_GRASS : SOIL.contains(below) && above == m;
        }
        if (PLANTS.contains(m)) {
            return SOIL.contains(below);
        }
        if (n.startsWith("DEAD_") && n.contains("CORAL") && !n.endsWith("_BLOCK") || m == Material.SEAGRASS
                || m == Material.SEA_PICKLE || m == Material.MOSS_CARPET || m == Material.AZALEA
                || m == Material.FLOWERING_AZALEA || m == Material.SAND || m == Material.GRAVEL) {
            return below != Material.AIR && below != Material.WATER;
        }
        return true;
    }

    private static int[] direction(String face) {
        return switch (face) {
            case "north" -> new int[]{0, 0, -1};
            case "south" -> new int[]{0, 0, 1};
            case "east" -> new int[]{1, 0, 0};
            case "west" -> new int[]{-1, 0, 0};
            case "up" -> new int[]{0, 1, 0};
            case "down" -> new int[]{0, -1, 0};
            default -> throw new IllegalArgumentException(face);
        };
    }

    /**
     * Where a player can get to on foot from the pad, with no flying and no placing blocks:
     * walking, jumping up one block, or dropping up to six (a fall costs a point of health for
     * each block past three, so a heart and a half at most). Conservative: anything with a
     * collision box counts as a full block, and fences and walls as a block and a half (nobody
     * jumps onto or over them). Amethyst buds and clusters are the exception: under half a
     * block, they're stepped over like a slab, no jump needed.
     */
    private static final class Walk {
        private static final Set<Material> OPEN = EnumSet.of(Material.AIR, Material.WATER, Material.SEAGRASS,
                Material.SHORT_GRASS, Material.TALL_GRASS, Material.FERN, Material.POPPY, Material.DANDELION,
                Material.OXEYE_DAISY, Material.AZURE_BLUET, Material.CORNFLOWER, Material.VINE, Material.GLOW_LICHEN,
                Material.MOSS_CARPET, Material.COBWEB, Material.HANGING_ROOTS);

        private static final int MAX_DROP = 6;

        final Blueprint b;
        final Set<Long> reached = new HashSet<>();
        final Map<Long, List<Long>> from = new HashMap<>();
        final long start;

        Walk(ExpeditionLayouts.Layout l) {
            this.b = l.blueprint();
            start = pack((int) Math.floor(l.padX()), (int) Math.floor(l.padY()), (int) Math.floor(l.padZ()));
            int[] bounds = b.bounds();
            ArrayDeque<Long> queue = new ArrayDeque<>(List.of(start));
            reached.add(start);
            while (!queue.isEmpty()) {
                long here = queue.poll();
                int[] p = unpack(here);
                for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    int nx = p[0] + d[0];
                    int nz = p[2] + d[1];
                    if (nx < bounds[0] - 1 || nx > bounds[3] + 1 || nz < bounds[2] - 1 || nz > bounds[5] + 1) {
                        continue;
                    }
                    Integer ny = step(p[0], p[1], p[2], nx, nz);
                    if (ny == null) {
                        if (overTheEdge(p[1], nx, nz, bounds[1])) {
                            // A fall off the island lands back on the pad (ExpeditionService#onMove).
                            from.computeIfAbsent(start, k -> new ArrayList<>()).add(here);
                        }
                        continue;
                    }
                    long next = pack(nx, ny, nz);
                    from.computeIfAbsent(next, k -> new ArrayList<>()).add(here);
                    if (reached.add(next)) {
                        queue.add(next);
                    }
                }
            }
        }

        /** Where a step from (x, y, z) toward (nx, nz) ends, or null if it can't be taken. */
        private Integer step(int x, int y, int z, int nx, int nz) {
            if (stand(nx, y + 1, nz) && !blocked(x, y + 2, z)) {
                return y + 1;
            }
            if (stand(nx, y, nz)) {
                return y;
            }
            if (blocked(nx, y, nz) || blocked(nx, y + 1, nz)) {
                return null;
            }
            for (int dy = 1; dy <= MAX_DROP; dy++) {
                if (blocked(nx, y - dy, nz)) {
                    return null;
                }
                if (stand(nx, y - dy, nz)) {
                    return y - dy;
                }
            }
            return null;
        }

        /** Whether stepping toward (nx, nz) from height y drops clear past the island. */
        private boolean overTheEdge(int y, int nx, int nz, int minY) {
            for (int yy = y + 1; yy >= minY - 1; yy--) {
                if (blocked(nx, yy, nz)) {
                    return false;
                }
            }
            return true;
        }

        /** Every place from which the pad can be walked back to (or fallen back to). */
        Set<Long> canReturn() {
            Set<Long> back = new HashSet<>(List.of(start));
            ArrayDeque<Long> queue = new ArrayDeque<>(List.of(start));
            while (!queue.isEmpty()) {
                for (long prev : from.getOrDefault(queue.poll(), List.of())) {
                    if (back.add(prev)) {
                        queue.add(prev);
                    }
                }
            }
            return back;
        }

        private boolean stand(int x, int y, int z) {
            Material floor = b.material(x, y - 1, z);
            return !open(floor) && !tall(floor) && !blocked(x, y, z) && !blocked(x, y + 1, z);
        }

        private boolean blocked(int x, int y, int z) {
            return !open(b.material(x, y, z)) || tall(b.material(x, y - 1, z));
        }

        private static boolean open(Material m) {
            String n = m.name();
            return OPEN.contains(m) || n.startsWith("DEAD_") && n.contains("CORAL") && !n.endsWith("_BLOCK")
                    || n.endsWith("AMETHYST_BUD") || m == Material.AMETHYST_CLUSTER;
        }

        private static boolean tall(Material m) {
            String n = m.name();
            return n.endsWith("_FENCE") || n.endsWith("_WALL") || n.endsWith("_FENCE_GATE");
        }

        static long pack(int x, int y, int z) {
            return ((long) (x + 1024) << 40) | ((long) (y + 1024) << 20) | (z + 1024);
        }

        static int[] unpack(long p) {
            return new int[]{(int) (p >> 40) - 1024, (int) ((p >> 20) & 0xFFFFF) - 1024, (int) (p & 0xFFFFF) - 1024};
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
