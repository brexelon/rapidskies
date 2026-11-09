package us.rapidskies.skyrealms;

import org.bukkit.plugin.java.JavaPlugin;

/** SkyRealms: Expeditions, Realm Tiers, Trade Routes, seasons (PLAN.md §5.1). */
public final class RapidSkyRealms extends JavaPlugin {

    @Override
    public void onEnable() {
        getSLF4JLogger().info("{} {} enabled", getName(), getPluginMeta().getVersion());
    }
}
