package us.rapidskies.hub;

import org.bukkit.plugin.java.JavaPlugin;

/** Hub: spawn, mode selector, parkour, leaderboards (PLAN.md §4). */
public final class RapidHub extends JavaPlugin {

    @Override
    public void onEnable() {
        getSLF4JLogger().info("{} {} enabled", getName(), getPluginMeta().getVersion());
    }
}
