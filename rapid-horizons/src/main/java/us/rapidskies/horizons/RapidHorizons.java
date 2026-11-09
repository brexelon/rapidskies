package us.rapidskies.horizons;

import org.bukkit.plugin.java.JavaPlugin;

/** Horizons SMP: Frontiers, Skyborne events, Trades, Chapters (PLAN.md §5.2). */
public final class RapidHorizons extends JavaPlugin {

    @Override
    public void onEnable() {
        getSLF4JLogger().info("{} {} enabled", getName(), getPluginMeta().getVersion());
    }
}
