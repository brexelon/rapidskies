package us.rapidskies.skyrealms.island;

import java.util.List;
import java.util.Locale;
import org.bukkit.Material;

/**
 * The starter islands (PLAN.md phase 4b: island schematics), built in code so they're reviewed,
 * versioned and tested like everything else, with no binary schematic files to keep in step.
 * rapid-skyrealms registers each with SuperiorSkyblock2 under its id ({@link IslandSchematics}).
 *
 * <p>Every layout gives the same start: about the same dirt, one tree (its saplings are what
 * differs), a sand patch for cactus and sugar cane, and the starter chest from the config. The
 * origin (0, 0, 0) is where a new member stands: on solid ground, with two blocks of air above.
 * Only blocks that exist in 1.20 are used (PLAN.md §3).
 */
public final class IslandLayouts {

    /** Every layout id, in menu order. */
    public static final List<String> IDS = List.of("meadow", "cherry", "atoll");

    private IslandLayouts() {
    }

    /**
     * A built layout. The chest at {@code chest} faces the spawn; {@code sapling} is the tree's
     * own (two go in the chest). {@code yaw} is the direction a new player faces.
     */
    public record Layout(String id, Blueprint blueprint, Material sapling, int chestX, int chestY, int chestZ,
                         float yaw) {
    }

    public static Layout build(String id) {
        return switch (id.toLowerCase(Locale.ROOT)) {
            case "meadow" -> meadow();
            case "cherry" -> cherry();
            case "atoll" -> atoll();
            default -> throw new IllegalArgumentException("no island layout '" + id + "' (have " + IDS + ")");
        };
    }

    /** The classic: a round grass island with a lobe to the east, an oak tree and a sand patch. */
    static Layout meadow() {
        Blueprint b = new Blueprint();
        body(b, Material.GRASS_BLOCK);
        // The east lobe of the classic L.
        for (int x = 5; x <= 7; x++) {
            for (int z = -1; z <= 1; z++) {
                b.put(x, -1, z, Material.GRASS_BLOCK);
                b.put(x, -2, z, Material.DIRT);
            }
        }
        sand(b);
        b.tree(2, 0, -2, 5, Material.OAK_LOG, Material.OAK_LEAVES, 2);
        b.put(-2, 0, -1, Material.CHEST, "[facing=east]");
        return new Layout("meadow", b, Material.OAK_SAPLING, -2, 0, -1, -90f);
    }

    /** A cherry grove: the same body with petals underfoot and a wide pink crown. */
    static Layout cherry() {
        Blueprint b = new Blueprint();
        body(b, Material.GRASS_BLOCK);
        // On grass only (petals pop off sand).
        for (int[] p : new int[][]{{-4, 0}, {-1, 3}, {3, 1}, {1, 3}, {-3, -2}}) {
            b.put(p[0], 0, p[1], Material.PINK_PETALS, "[facing=north,flower_amount=" + (2 + Math.abs(p[0]) % 3) + "]");
        }
        sand(b);
        b.tree(2, 0, -2, 5, Material.CHERRY_LOG, Material.CHERRY_LEAVES, 3);
        b.put(-2, 0, -1, Material.CHEST, "[facing=east]");
        return new Layout("cherry", b, Material.CHERRY_SAPLING, -2, 0, -1, -90f);
    }

    /** An atoll: a sand rim round a grass middle (dirt under both) and a tall jungle tree. */
    static Layout atoll() {
        Blueprint b = new Blueprint();
        body(b, Material.GRASS_BLOCK);
        for (int x = -5; x <= 5; x++) {
            for (int z = -5; z <= 5; z++) {
                double d = x * x + z * z;
                if (d > 9 && d <= 20.25) {
                    b.put(x, -1, z, Material.SAND);
                }
            }
        }
        b.tree(1, 0, -2, 6, Material.JUNGLE_LOG, Material.JUNGLE_LEAVES, 2);
        b.put(-2, 0, 1, Material.CHEST, "[facing=east]");
        return new Layout("atoll", b, Material.JUNGLE_SAPLING, -2, 0, 1, -90f);
    }

    /**
     * The shared body: a rounded island tapering to a point, {@code top} on the surface, dirt
     * under it and stone below that (with two coal ores to get torches going).
     */
    private static void body(Blueprint b, Material top) {
        b.disc(0, -1, 0, 4.5, top);
        b.disc(0, -2, 0, 4.5, Material.DIRT);
        b.disc(0, -3, 0, 3.6, Material.DIRT);
        b.disc(0, -4, 0, 2.6, Material.STONE);
        b.disc(0, -5, 0, 1.5, Material.STONE);
        b.put(0, -6, 0, Material.STONE);
        b.put(1, -4, 1, Material.COAL_ORE);
        b.put(-1, -4, -1, Material.COAL_ORE);
    }

    /** A sand patch on the south-west edge, for cactus and sugar cane. */
    private static void sand(Blueprint b) {
        b.put(-3, -1, 2, Material.SAND);
        b.put(-2, -1, 3, Material.SAND);
        b.put(-3, -1, 3, Material.SAND);
    }
}
