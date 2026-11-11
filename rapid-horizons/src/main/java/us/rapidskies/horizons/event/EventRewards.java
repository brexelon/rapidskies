package us.rapidskies.horizons.event;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import us.rapidskies.core.economy.ModeEconomy;
import us.rapidskies.horizons.HorizonsSettings.ItemReward;
import us.rapidskies.horizons.HorizonsSettings.Rewards;
import us.rapidskies.horizons.RapidHorizons;

/**
 * Pays an event's rewards to one participant: Crowns through the Horizons ledger, Bolts
 * through rapid-core's anti-farm rules (none while AFK or for shared-IP alts, a daily cap per
 * source; PLAN.md §6.2), and items into their inventory (whatever doesn't fit drops at their
 * feet). Ledger writes carry {@code txnBase}, so paying the same event twice pays once.
 */
public final class EventRewards {

    private final RapidHorizons plugin;

    public EventRewards(RapidHorizons plugin) {
        this.plugin = plugin;
    }

    /**
     * Main thread. {@code source} is the ledger reason ({@code skyborne}, {@code dragon});
     * {@code txnBase} identifies this event and player ({@code skyborne:<event>:<uuid>}).
     */
    public void pay(Player player, Rewards rewards, String source, String txnBase) {
        // Network XP and quests (PLAN.md §7): progression.yml xp.events, quests.yml objectives.
        plugin.core().levels().award(player, source);
        plugin.core().quests().record(player, source, 1);
        ModeEconomy economy = plugin.core().economy();
        if (rewards.crowns() > 0 && economy != null) {
            plugin.core().database().async(() -> economy.deposit(player.getUniqueId(), rewards.crowns(), source,
                    txnBase + ":crowns")).thenAccept(change -> {
                if (change.success()) {
                    plugin.getServer().getScheduler().runTask(plugin, () -> plugin.lang().send(player,
                            "rewards.crowns", Placeholder.unparsed("amount", Long.toString(rewards.crowns()))));
                }
            }).exceptionally(e -> {
                plugin.getSLF4JLogger().warn("Couldn't pay {} Crowns to {} for {}: {}", rewards.crowns(),
                        player.getName(), source, e.getMessage());
                return null;
            });
        }
        if (rewards.bolts() > 0) {
            plugin.core().earning().award(player, rewards.bolts(), source, rewards.boltsDailyCap(), txnBase + ":bolts")
                    .thenAccept(paid -> {
                        if (paid > 0) {
                            plugin.getServer().getScheduler().runTask(plugin, () -> plugin.lang().send(player,
                                    "rewards.bolts", Placeholder.unparsed("amount", Long.toString(paid))));
                        }
                    }).exceptionally(e -> {
                        plugin.getSLF4JLogger().warn("Couldn't pay Bolts to {} for {}: {}", player.getName(), source,
                                e.getMessage());
                        return null;
                    });
        }
        List<Component> names = new ArrayList<>();
        for (ItemReward item : rewards.items()) {
            Material material = Material.matchMaterial(item.material());
            if (material == null || !material.isItem()) {
                plugin.getSLF4JLogger().warn("Reward item '{}' doesn't exist on this version", item.material());
                continue;
            }
            ItemStack stack = new ItemStack(material, item.amount());
            player.getInventory().addItem(stack).values()
                    .forEach(rest -> player.getWorld().dropItemNaturally(player.getLocation(), rest));
            names.add(Component.text(item.amount() + "× ").append(Component.translatable(material.translationKey())));
        }
        if (!names.isEmpty()) {
            plugin.lang().send(player, "rewards.items",
                    Placeholder.component("items", Component.join(net.kyori.adventure.text.JoinConfiguration.commas(true),
                            names)));
        }
    }
}
