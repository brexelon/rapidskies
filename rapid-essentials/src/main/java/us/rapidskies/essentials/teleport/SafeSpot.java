package us.rapidskies.essentials.teleport;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Finds where a player can actually stand near a remembered spot ({@code /back}, homes):
 * the spot itself, else the nearest safe footing in the same column. Main thread, with the
 * chunk loaded.
 */
public final class SafeSpot {

    /** How far up or down from the spot to look for safe footing. */
    private static final int SEARCH = 12;

    private SafeSpot() {
    }

    /**
     * {@code at} itself if a player can stand there, else the nearest safe footing below it
     * (they were flying), else above it (they died in lava or a wall), else null.
     */
    public static Location find(Location at) {
        if (fitsExactly(at)) {
            return at;
        }
        for (int dy = 1; dy <= SEARCH; dy++) {
            Block below = at.getBlock().getRelative(0, -dy, 0);
            if (standable(below)) {
                return centred(at, below);
            }
        }
        for (int dy = 1; dy <= SEARCH; dy++) {
            Block above = at.getBlock().getRelative(0, dy, 0);
            if (standable(above)) {
                return centred(at, above);
            }
        }
        return null;
    }

    /** Something solid somewhere below: not mid-fall into the void. */
    public static boolean hasGround(Location at) {
        World world = at.getWorld();
        int x = at.getBlockX();
        int z = at.getBlockZ();
        for (int y = Math.min(at.getBlockY(), world.getMaxHeight() - 1); y >= world.getMinHeight(); y--) {
            if (world.getBlockAt(x, y, z).getType().isSolid()) {
                return true;
            }
        }
        return false;
    }

    /** The exact spot works: standing in open air on solid ground, or on top of a slab, carpet or stairs. */
    private static boolean fitsExactly(Location at) {
        Block block = at.getBlock();
        if (block.isPassable()) {
            return standable(block);
        }
        // Inside a partial block's cell but on top of it: that block is the ground.
        Block above = block.getRelative(0, 1, 0);
        Block head = above.getRelative(0, 1, 0);
        return block.getBoundingBox().getMaxY() <= at.getY() + 1e-3
                && SafeBlocks.safeGround(block.getType().name())
                && above.isPassable() && head.isPassable()
                && SafeBlocks.safeSpace(above.getType().name()) && SafeBlocks.safeSpace(head.getType().name());
    }

    private static Location centred(Location at, Block feet) {
        return new Location(at.getWorld(), feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, at.getYaw(),
                at.getPitch());
    }

    /** Solid, harmless ground below {@code feet}, and room for a player that won't drown or burn them. */
    private static boolean standable(Block feet) {
        Block ground = feet.getRelative(0, -1, 0);
        Block head = feet.getRelative(0, 1, 0);
        String groundType = ground.getType().name();
        return ground.getType().isSolid()
                && (SafeBlocks.safeGround(groundType) || groundType.endsWith("LEAVES"))
                && feet.isPassable() && head.isPassable()
                && SafeBlocks.safeSpace(feet.getType().name()) && SafeBlocks.safeSpace(head.getType().name());
    }
}
