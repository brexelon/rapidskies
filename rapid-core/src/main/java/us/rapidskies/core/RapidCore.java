package us.rapidskies.core;

import org.bukkit.plugin.java.JavaPlugin;

/** Profiles, Bolts, chat, cosmetics, staff mode and the GUI framework (PLAN.md §4). */
public final class RapidCore extends JavaPlugin {

    @Override
    public void onEnable() {
        getSLF4JLogger().info("{} {} enabled", getName(), getPluginMeta().getVersion());
    }
}
