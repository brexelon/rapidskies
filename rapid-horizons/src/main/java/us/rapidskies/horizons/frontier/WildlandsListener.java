package us.rapidskies.horizons.frontier;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import us.rapidskies.horizons.HorizonsSettings;
import us.rapidskies.horizons.RapidHorizons;

/**
 * The Wildlands (PLAN.md §5.2): the Frontier's outer ring, where PvP is forced (the hook is
 * {@code PvpService#setWildlands}) and resources are richer, a risk/reward choice for
 * players who want it.
 *
 * <ul>
 *   <li>Crossing in shows a warning title and keeps a red boss bar up while inside (both
 *       render on 1.20); crossing out says so on the action bar.</li>
 *   <li>Natural ores (the ones a Miner is paid for) drop double with
 *       {@code ore-bonus-percent} chance; hostile mobs (not from spawners) with
 *       {@code loot-bonus-percent}. Trades pay {@code trade-bonus-percent} more
 *       ({@code TradeService#setIncomeModifier}).</li>
 * </ul>
 */
public final class WildlandsListener implements Listener {

    private final RapidHorizons plugin;
    private final FrontierService frontier;
    private final Map<UUID, BossBar> bars = new ConcurrentHashMap<>();

    public WildlandsListener(RapidHorizons plugin, FrontierService frontier) {
        this.plugin = plugin;
        this.frontier = frontier;
    }

    private HorizonsSettings.Wildlands settings() {
        return plugin.settings().frontier().wildlands();
    }

    // ------------------------------------------------------------------ notices

    private void update(Player player, Location to, boolean announce) {
        boolean inside = frontier.inWildlands(to);
        BossBar bar = bars.get(player.getUniqueId());
        if (inside && bar == null) {
            bar = BossBar.bossBar(plugin.lang().get("wildlands.bar"), 1f, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
            bars.put(player.getUniqueId(), bar);
            player.showBossBar(bar);
            if (announce) {
                player.showTitle(Title.title(plugin.lang().get("wildlands.title"),
                        plugin.lang().get("wildlands.subtitle"),
                        Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(700))));
                plugin.lang().send(player, plugin.pvp().enabled(player.getUniqueId()) ? "wildlands.entered"
                        : "wildlands.entered-pvp-off");
            }
        } else if (!inside && bar != null) {
            player.hideBossBar(bar);
            bars.remove(player.getUniqueId());
            if (announce) {
                player.sendActionBar(plugin.lang().get("wildlands.left"));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedBlock() || frontier.place(event.getTo().getWorld()) != FrontierService.Place.FRONTIER
                && !bars.containsKey(event.getPlayer().getUniqueId())) {
            return;
        }
        update(event.getPlayer(), event.getTo(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        // After the teleport has happened (a cancelled or changed one included).
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                update(player, player.getLocation(), true);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        update(event.getPlayer(), event.getPlayer().getLocation(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        update(event.getPlayer(), event.getPlayer().getLocation(), true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        BossBar bar = bars.remove(event.getPlayer().getUniqueId());
        if (bar != null) {
            event.getPlayer().hideBossBar(bar);
        }
    }

    /** Hides every bar (plugin disabling). */
    public void clear() {
        for (Map.Entry<UUID, BossBar> e : bars.entrySet()) {
            Player p = plugin.getServer().getPlayer(e.getKey());
            if (p != null) {
                p.hideBossBar(e.getValue());
            }
        }
        bars.clear();
    }

    // ------------------------------------------------------------------ richer resources

    private static boolean roll(double percent) {
        return percent > 0 && ThreadLocalRandom.current().nextDouble() * 100 < percent;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOreDrops(BlockDropItemEvent event) {
        Block block = event.getBlock();
        if (!frontier.inWildlands(block.getLocation()) || !plugin.trades().brokeNaturally(block)
                || !plugin.trades().isOre(event.getBlockState().getType()) || !roll(settings().oreBonusPercent())) {
            return;
        }
        for (Item item : event.getItems()) {
            ItemStack stack = item.getItemStack();
            stack.setAmount(Math.min(stack.getMaxStackSize(), stack.getAmount() * 2));
            item.setItemStack(stack);
        }
        event.getPlayer().sendActionBar(plugin.lang().get("wildlands.bonus-ore"));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLoot(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        if (!(dead instanceof Enemy) || dead.getKiller() == null || dead.fromMobSpawner()
                || dead.getEntitySpawnReason() == CreatureSpawnEvent.SpawnReason.CUSTOM
                || !frontier.inWildlands(dead.getLocation()) || !roll(settings().lootBonusPercent())) {
            return;
        }
        List<ItemStack> extra = new ArrayList<>();
        for (ItemStack drop : event.getDrops()) {
            extra.add(drop.clone());
        }
        event.getDrops().addAll(extra);
    }
}
