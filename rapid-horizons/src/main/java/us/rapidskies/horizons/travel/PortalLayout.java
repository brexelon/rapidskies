package us.rapidskies.horizons.travel;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import us.rapidskies.horizons.HorizonsSettings.Region;

/**
 * The arch around the spawn portal, as blocks (free of Bukkit, so it's unit-tested). The
 * region is the opening: one block thick, filled with nether portal blocks. Around it, in the
 * CottageCore spawn's palette and only with blocks every 1.20+ client has:
 *
 * <pre>
 *     L f f f f f L        L  lantern            f  flowering azalea leaves
 *     s s s s s s s        s  stripped spruce log (the lintel)
 *     m P P P P P m        m  mossy stone bricks P  nether portal (the region)
 *     m P P P P P m
 *     m P P P P P m
 *     m P P P P P m
 *     c P P P P P c        c  chiseled stone bricks (column bases)
 * </pre>
 *
 * The ground under the opening is left alone, so the path still runs through it.
 */
public final class PortalLayout {

    /** One block: position and block data string ({@code minecraft:lantern[hanging=false]}). */
    public record Placement(int x, int y, int z, String data) {
    }

    private PortalLayout() {
    }

    /** The arch for {@code opening}, or empty if it isn't one block thick along x or z. */
    public static Optional<List<Placement>> of(Region opening) {
        boolean alongX = opening.minZ() == opening.maxZ();
        boolean alongZ = opening.minX() == opening.maxX();
        if (!alongX && !alongZ) {
            return Optional.empty();
        }
        // A 1×1 opening could face either way; take x.
        String axis = alongX ? "x" : "z";
        int from = alongX ? opening.minX() : opening.minZ();
        int to = alongX ? opening.maxX() : opening.maxZ();
        int across = alongX ? opening.minZ() : opening.minX();
        int bottom = opening.minY();
        int top = opening.maxY();

        List<Placement> out = new ArrayList<>();
        Put put = (a, y, data) -> out.add(alongX ? new Placement(a, y, across, data) : new Placement(across, y, a, data));
        for (int y = bottom; y <= top; y++) {
            String column = y == bottom ? "minecraft:chiseled_stone_bricks" : "minecraft:mossy_stone_bricks";
            put.at(from - 1, y, column);
            put.at(to + 1, y, column);
            for (int a = from; a <= to; a++) {
                put.at(a, y, "minecraft:nether_portal[axis=" + axis + "]");
            }
        }
        for (int a = from - 1; a <= to + 1; a++) {
            put.at(a, top + 1, "minecraft:stripped_spruce_log[axis=" + axis + "]");
        }
        put.at(from - 1, top + 2, "minecraft:lantern[hanging=false,waterlogged=false]");
        put.at(to + 1, top + 2, "minecraft:lantern[hanging=false,waterlogged=false]");
        for (int a = from; a <= to; a++) {
            put.at(a, top + 2, "minecraft:flowering_azalea_leaves[persistent=true]");
        }
        return Optional.of(List.copyOf(out));
    }

    @FunctionalInterface
    private interface Put {
        void at(int along, int y, String data);
    }
}
