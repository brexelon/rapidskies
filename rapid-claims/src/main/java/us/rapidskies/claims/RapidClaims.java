package us.rapidskies.claims;

import org.bukkit.plugin.java.JavaPlugin;

/** Chunk claims, trust roles, towns, plots and Town Projects (PLAN.md §4.1). */
public final class RapidClaims extends JavaPlugin {

    @Override
    public void onEnable() {
        getSLF4JLogger().info("{} {} enabled", getName(), getPluginMeta().getVersion());
    }
}
