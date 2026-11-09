package us.rapidskies.market;

import org.bukkit.plugin.java.JavaPlugin;

/** Auction house, scoped per mode and currency (PLAN.md §4.2). */
public final class RapidMarket extends JavaPlugin {

    @Override
    public void onEnable() {
        getSLF4JLogger().info("{} {} enabled", getName(), getPluginMeta().getVersion());
    }
}
