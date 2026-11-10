package us.rapidskies.horizons.frontier;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import us.rapidskies.horizons.HorizonsSettings;
import us.rapidskies.horizons.RapidHorizons;

/**
 * The rules that come with a Frontier's modifier, in its overworld only (the terrain side is
 * {@code frontier.terrain}).
 *
 * <ul>
 *   <li><b>Overgrowth</b>: a share of natural hostile spawns become a mob from the
 *       configured pool (husks, strays, witches...), so the forests feel alive.</li>
 *   <li><b>Drought</b>: no rain; water poured from a bucket boils away like in the Nether
 *       (cauldrons and bottles still work, natural water stays) and melted or broken ice
 *       leaves nothing; and under the open sky by day, without a helmet, hunger drains
 *       faster. Water is precious.</li>
 *   <li><b>Shattered Skies</b>: no extra rules; the islands and the sea below them are the
 *       whole of it.</li>
 *   <li><b>Deep Dark</b>: natural ores (the ones a Miner is paid for) broken below
 *       {@code deep-y} drop double with {@code deep-ore-bonus-percent} chance, so the caverns
 *       are worth the wardens. Not stacked with the Wildlands' own bonus.</li>
 * </ul>
 */
public final class ModifierEffects implements Listener {

    private final RapidHorizons plugin;
    private final FrontierService frontier;
    private final Map<UUID, Long> sunNotice = new ConcurrentHashMap<>();
    private boolean replacing;
    private BukkitTask sun;

    public ModifierEffects(RapidHorizons plugin, FrontierService frontier) {
        this.plugin = plugin;
        this.frontier = frontier;
    }

    private HorizonsSettings.Frontier settings() {
        return plugin.settings().frontier();
    }

    public void start() {
        sun = Bukkit.getScheduler().runTaskTimer(plugin, this::sunTick, 40L, 40L);
    }

    public void stop() {
        if (sun != null) {
            sun.cancel();
        }
    }

    /** The open Frontier's modifier if {@code world} is its overworld. */
    private Modifier modifierOf(World world) {
        if (frontier.place(world) != FrontierService.Place.FRONTIER) {
            return null;
        }
        return frontier.current().map(FrontierRow::modifier).orElse(null);
    }

    // ------------------------------------------------------------------ Overgrowth

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (replacing || event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL
                || !(event.getEntity() instanceof Enemy)) {
            return;
        }
        Location at = event.getLocation();
        if (modifierOf(at.getWorld()) != Modifier.OVERGROWTH) {
            return;
        }
        HorizonsSettings.Overgrowth o = settings().overgrowth();
        List<String> pool = o.mobPool();
        if (pool.isEmpty() || ThreadLocalRandom.current().nextDouble() * 100 >= o.mobVarietyPercent()) {
            return;
        }
        EntityType type = Registry.ENTITY_TYPE.get(NamespacedKey.minecraft(
                pool.get(ThreadLocalRandom.current().nextInt(pool.size()))));
        if (type == null || type == event.getEntityType() || type.getEntityClass() == null || !type.isSpawnable()) {
            return;
        }
        event.setCancelled(true);
        replacing = true;
        try {
            Class<? extends Entity> cls = type.getEntityClass();
            at.getWorld().spawn(at, cls, CreatureSpawnEvent.SpawnReason.NATURAL);
        } catch (IllegalArgumentException e) {
            plugin.getSLF4JLogger().warn("frontier.overgrowth.mob-pool: can't spawn {}: {}", type, e.getMessage());
        } finally {
            replacing = false;
        }
    }

    // ------------------------------------------------------------------ Drought

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPour(PlayerBucketEmptyEvent event) {
        if (event.getBucket() != Material.WATER_BUCKET || !settings().drought().evaporateWater()
                || modifierOf(event.getBlock().getWorld()) != Modifier.DROUGHT) {
            return;
        }
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        event.setCancelled(true);
        Block at = event.getBlock();
        Location fx = at.getLocation().add(0.5, 0.5, 0.5);
        at.getWorld().spawnParticle(Particle.LARGE_SMOKE, fx, 8, 0.3, 0.3, 0.3, 0.01);
        at.getWorld().playSound(fx, Sound.BLOCK_FIRE_EXTINGUISH, 0.6f, 1.6f);
        // The bucket empties, as in the Nether.
        ItemStack hand = player.getInventory().getItem(event.getHand());
        if (hand != null && hand.getType() == Material.WATER_BUCKET) {
            player.getInventory().setItem(event.getHand(), new ItemStack(Material.BUCKET));
        }
        plugin.lang().send(player, "frontier.drought.evaporated");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMelt(BlockFadeEvent event) {
        Block block = event.getBlock();
        if (block.getType() == Material.ICE && settings().drought().evaporateWater()
                && modifierOf(block.getWorld()) == Modifier.DROUGHT) {
            event.setCancelled(true);
            block.setType(Material.AIR);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIceBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getType() != Material.ICE || !settings().drought().evaporateWater()
                || modifierOf(block.getWorld()) != Modifier.DROUGHT) {
            return;
        }
        // Vanilla leaves water where ice over a solid block breaks; it boils away instead.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (block.getType() == Material.WATER) {
                block.setType(Material.AIR);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onWeather(WeatherChangeEvent event) {
        if (event.toWeatherState() && modifierOf(event.getWorld()) == Modifier.DROUGHT) {
            event.setCancelled(true);
        }
    }

    /** Every 2 s: the Drought sun drains hunger from bare heads under the open sky, by day. */
    private void sunTick() {
        double exhaustion = settings().drought().sunExhaustion();
        World world = frontier.overworld();
        if (exhaustion <= 0 || world == null || modifierOf(world) != Modifier.DROUGHT) {
            return;
        }
        long time = world.getTime();
        if (time > 12000) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Player p : world.getPlayers()) {
            GameMode mode = p.getGameMode();
            if ((mode != GameMode.SURVIVAL && mode != GameMode.ADVENTURE) || p.getInventory().getHelmet() != null
                    || p.isInWater() || p.getLocation().getY() < settings().drought().waterLevel()
                    || world.getHighestBlockYAt(p.getLocation()) > p.getLocation().getBlockY()) {
                continue;
            }
            p.setExhaustion(p.getExhaustion() + (float) (exhaustion * 2));
            Long last = sunNotice.get(p.getUniqueId());
            if (last == null || now - last > 10 * 60_000L) {
                sunNotice.put(p.getUniqueId(), now);
                plugin.lang().send(p, "frontier.drought.sun");
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sunNotice.remove(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ Deep Dark

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDeepOre(BlockDropItemEvent event) {
        Block block = event.getBlock();
        HorizonsSettings.DeepDark d = settings().deepDark();
        if (d.deepOreBonusPercent() <= 0 || block.getY() >= d.deepY()
                || modifierOf(block.getWorld()) != Modifier.DEEP_DARK
                || frontier.inWildlands(block.getLocation())
                || !plugin.trades().isOre(event.getBlockState().getType()) || !plugin.trades().brokeNaturally(block)
                || ThreadLocalRandom.current().nextDouble() * 100 >= d.deepOreBonusPercent()) {
            return;
        }
        for (Item item : event.getItems()) {
            ItemStack stack = item.getItemStack();
            stack.setAmount(Math.min(stack.getMaxStackSize(), stack.getAmount() * 2));
            item.setItemStack(stack);
        }
        event.getPlayer().sendActionBar(plugin.lang().get("frontier.deep-dark.bonus-ore"));
    }
}
