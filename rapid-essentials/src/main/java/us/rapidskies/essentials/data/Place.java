package us.rapidskies.essentials.data;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;

/**
 * A stored position (a home or a warp). The world is kept by key ({@code minecraft:overworld},
 * {@code rapidskies:spawn}), which stays the same across restarts, unlike a world's UUID.
 */
public record Place(String world, double x, double y, double z, float yaw, float pitch) {

    public static Place of(Location at) {
        return new Place(at.getWorld().getKey().asString(), at.getX(), at.getY(), at.getZ(), at.getYaw(),
                at.getPitch());
    }

    /** The location, or null if its world isn't loaded (a retired Frontier). */
    public Location toLocation() {
        NamespacedKey key = NamespacedKey.fromString(world);
        World w = key == null ? null : Bukkit.getWorld(key);
        return w == null ? null : new Location(w, x, y, z, yaw, pitch);
    }
}
