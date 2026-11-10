package us.rapidskies.horizons.frontier;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.popcraft.chunky.api.ChunkyAPI;

/**
 * Pre-generation through Chunky's API (PLAN.md §4: pre-generate every new Frontier before it
 * opens). Chunky is optional at runtime (softdepend): only this class touches its types, and
 * it's only loaded when Chunky is enabled. Chunky keeps its own tasks across restarts.
 */
final class ChunkyPregen {

    private final ChunkyAPI chunky;

    private ChunkyPregen(ChunkyAPI chunky) {
        this.chunky = chunky;
    }

    /** The hook, or null when Chunky hasn't registered its API. */
    static ChunkyPregen create() {
        ChunkyAPI api = Bukkit.getServicesManager().load(ChunkyAPI.class);
        return api == null ? null : new ChunkyPregen(api);
    }

    /** Starts generating the square {@code radius} around the border's centre; false if Chunky refused. */
    boolean start(World world, double radius) {
        String name = world.getName();
        if (chunky.isRunning(name)) {
            return true;
        }
        var border = world.getWorldBorder().getCenter();
        return chunky.startTask(name, "square", border.getX(), border.getZ(), radius, radius, "concentric");
    }

    boolean running(World world) {
        return chunky.isRunning(world.getName());
    }

    /** Stops a task on a world about to be unloaded. */
    void cancel(World world) {
        if (chunky.isRunning(world.getName())) {
            chunky.cancelTask(world.getName());
        }
    }
}
