package us.rapidskies.horizons.trade;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import us.rapidskies.common.time.Durations;
import us.rapidskies.core.RapidCore;
import us.rapidskies.core.economy.ModeEconomy;
import us.rapidskies.horizons.HorizonsSettings;
import us.rapidskies.horizons.RapidHorizons;
import us.rapidskies.horizons.data.HorizonsRepository;
import us.rapidskies.horizons.data.HorizonsRepository.TradeRow;

/**
 * Trades (PLAN.md §5.2): players pick up to two professions and earn Trade XP and Crowns
 * from matching work.
 *
 * <ul>
 *   <li>Miner: natural ores. Farmer: fully grown crops, melons and pumpkins, berries.
 *       Ores, melons and pumpkins a player placed pay nothing ({@link PlacedBlocks}).</li>
 *   <li>Hunter: hostile mobs. Mobs from spawners pay {@code spawner-mob-percent}; mobs from
 *       spawn eggs and commands pay nothing.</li>
 *   <li>Builder: placed blocks, once per position per day. Enchanter: levels spent at a
 *       table. Fisher: catches, by fish, treasure or junk.</li>
 * </ul>
 *
 * <p>Nothing is earned while AFK (fishing and mob farms run unattended), in creative, or
 * past the daily soft cap's diminishing returns (PLAN.md §5.3); Tailwind multiplies XP and
 * Crowns. Crowns are paid in batches off the main thread every {@code payout-seconds} and
 * on quit, so a mob farm doesn't mean a database write per kill.
 *
 * <p>Side-grade perks per Trade level (PLAN.md §5.2) are a later step.
 */
public final class TradeService implements Listener {

    public static final String NO_COOLDOWN = "rapid.horizons.trades.nocooldown";

    private final RapidHorizons plugin;
    private final HorizonsRepository repository;
    private final PlacedBlocks placed;
    private final Map<UUID, TradeProfile> profiles = new ConcurrentHashMap<>();
    /** Writes in flight per player: a quick rejoin waits for them before loading. */
    private final Map<UUID, CompletableFuture<?>> saving = new ConcurrentHashMap<>();
    private BukkitTask payouts;
    private volatile boolean warnedNoEconomy;

    public TradeService(RapidHorizons plugin, HorizonsRepository repository) {
        this.plugin = plugin;
        this.repository = repository;
        this.placed = new PlacedBlocks(plugin);
    }

    private HorizonsSettings.Trades settings() {
        return plugin.settings().trades();
    }

    private TradeRules rules() {
        return settings().rules();
    }

    private RapidCore core() {
        return plugin.core();
    }

    public void start() {
        long period = settings().payoutSeconds() * 20L;
        payouts = plugin.getServer().getScheduler().runTaskTimer(plugin, this::flushAll, period, period);
    }

    /** Writes everything still pending. Blocks: for onDisable, while the database is still open. */
    public void stop() {
        if (payouts != null) {
            payouts.cancel();
        }
        for (TradeProfile profile : List.copyOf(profiles.values())) {
            try {
                write(snapshot(profile));
            } catch (SQLException e) {
                plugin.getSLF4JLogger().error("Couldn't save Trades of {} at shutdown: {}", profile.id, e.getMessage());
            }
        }
        saving.values().forEach(CompletableFuture::join);
    }

    // ------------------------------------------------------------------ reading, for the menu and claims

    /** Whether {@code player}'s Trades are loaded (they're online here). */
    public boolean loaded(UUID player) {
        return profiles.containsKey(player);
    }

    public record View(Trade trade, int level, double xp, double progress, boolean active) {
    }

    /** {@code player}'s Trades for display, every Trade included. */
    public List<View> view(UUID player) {
        TradeProfile p = profiles.get(player);
        List<View> out = new ArrayList<>();
        for (Trade t : Trade.values()) {
            TradeProfile.Progress pr = p == null ? null : p.progress.get(t);
            double xp = pr == null ? 0 : pr.xp;
            int level = pr == null ? 0 : pr.level;
            out.add(new View(t, level, xp, rules().progress(xp, level), pr != null && pr.active));
        }
        return out;
    }

    public int activeCount(UUID player) {
        TradeProfile p = profiles.get(player);
        return p == null ? 0 : p.activeCount();
    }

    /** Time until {@code player} may drop a Trade again (zero: now). */
    public Duration dropCooldown(Player player) {
        TradeProfile p = profiles.get(player.getUniqueId());
        if (p == null || player.hasPermission(NO_COOLDOWN)) {
            return Duration.ZERO;
        }
        return TradeRules.cooldownLeft(p.tradesChangedAt, Instant.now(),
                Duration.ofHours(settings().switchCooldownHours()));
    }

    /** Extra personal claim chunks from {@code player}'s Trade levels (0 when they aren't online here). */
    public int claimChunks(UUID player) {
        TradeProfile p = profiles.get(player);
        return p == null ? 0 : rules().claimChunks(p.totalLevels());
    }

    // ------------------------------------------------------------------ picking and dropping

    public void pick(Player player, Trade trade) {
        TradeProfile p = profiles.get(player.getUniqueId());
        if (p == null) {
            plugin.lang().send(player, "trades.not-loaded");
            return;
        }
        Component name = name(trade);
        if (p.active(trade)) {
            plugin.lang().send(player, "trades.already-active", Placeholder.component("trade", name));
            return;
        }
        if (p.activeCount() >= rules().maxActive()) {
            plugin.lang().send(player, "trades.full",
                    Placeholder.unparsed("max", Integer.toString(rules().maxActive())));
            return;
        }
        TradeProfile.Progress pr = p.of(trade);
        pr.active = true;
        pr.dirty = true;
        plugin.lang().send(player, "trades.picked", Placeholder.component("trade", name));
    }

    public void drop(Player player, Trade trade) {
        TradeProfile p = profiles.get(player.getUniqueId());
        if (p == null) {
            plugin.lang().send(player, "trades.not-loaded");
            return;
        }
        Component name = name(trade);
        if (!p.active(trade)) {
            plugin.lang().send(player, "trades.not-active", Placeholder.component("trade", name));
            return;
        }
        Duration left = dropCooldown(player);
        if (!left.isZero()) {
            plugin.lang().send(player, "trades.cooldown", Placeholder.unparsed("time", Durations.format(left)));
            return;
        }
        TradeProfile.Progress pr = p.of(trade);
        pr.active = false;
        pr.dirty = true;
        Instant now = Instant.now();
        p.tradesChangedAt = now;
        UUID id = player.getUniqueId();
        track(id, core().database().async(() -> {
            repository.saveTradesChanged(id, now);
            return null;
        }));
        plugin.lang().send(player, "trades.dropped", Placeholder.component("trade", name),
                Placeholder.unparsed("hours", Integer.toString(settings().switchCooldownHours())));
    }

    public Component name(Trade trade) {
        return plugin.lang().get("trades.name." + trade.id());
    }

    // ------------------------------------------------------------------ earning

    private void award(Player player, Trade trade, double baseXp) {
        if (baseXp <= 0) {
            return;
        }
        TradeProfile p = profiles.get(player.getUniqueId());
        if (p == null || !p.active(trade)) {
            return;
        }
        GameMode mode = player.getGameMode();
        if ((mode != GameMode.SURVIVAL && mode != GameMode.ADVENTURE) || core().afk().isAfk(player)) {
            return;
        }
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        p.rollDay(today);
        TradeProfile.Progress pr = p.of(trade);
        TradeRules.Reward reward = rules().reward(baseXp, settings().source(trade).crownsPerXp(), pr.level,
                (long) p.pointsToday, core().tailwind().multiplier(player));
        p.pointsToday += baseXp;
        p.pendingPoints.merge(today, baseXp, Double::sum);
        pr.xp += reward.xp();
        pr.dirty = true;
        int level = rules().levelFor(pr.xp);
        if (level > pr.level) {
            pr.level = level;
            plugin.lang().send(player, "trades.level-up", Placeholder.component("trade", name(trade)),
                    Placeholder.unparsed("level", Integer.toString(level)));
        }
        if (reward.crowns() > 0) {
            p.pendingCrowns.merge(trade, reward.crowns(), Double::sum);
        }
    }

    private boolean rewardedOnBreak(Block block) {
        String key = block.getType().getKey().getKey();
        return settings().source(Trade.MINER).xpFor(key) > 0
                || (!(block.getBlockData() instanceof Ageable) && settings().source(Trade.FARMER).xpFor(key) > 0);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        if (rewardedOnBreak(block)) {
            placed.mark(block);
        }
        Player player = event.getPlayer();
        TradeProfile p = profiles.get(player.getUniqueId());
        if (p != null && p.active(Trade.BUILDER)) {
            p.rollDay(LocalDate.now(ZoneOffset.UTC));
            long packed = ((long) (block.getX() & 0x3FFFFFF) << 38) | ((long) (block.getZ() & 0x3FFFFFF) << 12)
                    | (block.getY() & 0xFFF);
            if (p.firstBuildAt(packed)) {
                award(player, Trade.BUILDER, settings().source(Trade.BUILDER).xpFor("block"));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        boolean wasPlaced = placed.take(block);
        if (wasPlaced) {
            return;
        }
        Player player = event.getPlayer();
        String key = block.getType().getKey().getKey();
        award(player, Trade.MINER, settings().source(Trade.MINER).xpFor(key));
        if (block.getBlockData() instanceof Ageable crop && crop.getAge() < crop.getMaximumAge()) {
            return;
        }
        award(player, Trade.FARMER, settings().source(Trade.FARMER).xpFor(key));
    }

    /** Sweet berries and glow berries are picked by right-clicking, not breaking. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHarvest(PlayerHarvestBlockEvent event) {
        if (event.getItemsHarvested().isEmpty()) {
            return;
        }
        String key = event.getHarvestedBlock().getType().getKey().getKey();
        award(event.getPlayer(), Trade.FARMER, settings().source(Trade.FARMER).xpFor(key));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKill(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        Player killer = dead.getKiller();
        if (killer == null || !(dead instanceof Enemy)) {
            return;
        }
        CreatureSpawnEvent.SpawnReason reason = dead.getEntitySpawnReason();
        if (reason == CreatureSpawnEvent.SpawnReason.SPAWNER_EGG || reason == CreatureSpawnEvent.SpawnReason.DISPENSE_EGG
                || reason == CreatureSpawnEvent.SpawnReason.COMMAND || reason == CreatureSpawnEvent.SpawnReason.CUSTOM) {
            return;
        }
        double xp = settings().source(Trade.HUNTER).xpFor(dead.getType().getKey().getKey());
        if (dead.fromMobSpawner()) {
            xp *= settings().spawnerMobPercent() / 100.0;
        }
        award(killer, Trade.HUNTER, xp);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent event) {
        award(event.getEnchanter(), Trade.ENCHANTER,
                event.getExpLevelCost() * settings().source(Trade.ENCHANTER).xpFor("level"));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH || !(event.getCaught() instanceof Item item)) {
            return;
        }
        ItemStack stack = item.getItemStack();
        String group = FishCatch.classify(stack.getType().name(), !stack.getEnchantments().isEmpty()
                || stack.getType().name().equals("ENCHANTED_BOOK"));
        award(event.getPlayer(), Trade.FISHER, settings().source(Trade.FISHER).xpFor(group));
    }

    // ------------------------------------------------------------------ loading and saving

    // MONITOR: only logins that go ahead. Async thread, so blocking is fine.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        UUID id = event.getUniqueId();
        CompletableFuture<?> pending = saving.get(id);
        if (pending != null) {
            pending.join();
        }
        try {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            Map<Trade, TradeRow> rows = repository.trades(id);
            HorizonsRepository.PlayerRow row = repository.player(id);
            long points = repository.dailyPoints(id, today);
            profiles.put(id, new TradeProfile(id, rows, row.tradesChangedAt().orElse(null), today, points, rules()));
        } catch (SQLException e) {
            // Without a profile they simply earn nothing this session; nothing is lost.
            plugin.getSLF4JLogger().warn("Couldn't load Trades of {}: {}", event.getName(), e.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        TradeProfile p = profiles.remove(event.getPlayer().getUniqueId());
        if (p != null) {
            Batch batch = snapshot(p);
            track(p.id, core().database().async(() -> {
                write(batch);
                return null;
            }));
        }
    }

    /** Everything one write sends; taken on the main thread, written off it. */
    private record Batch(UUID player, Map<Trade, TradeRow> rows, Map<Trade, Long> crowns,
                         Map<LocalDate, Long> points) {
        boolean empty() {
            return rows.isEmpty() && crowns.isEmpty() && points.isEmpty();
        }
    }

    /** Takes what's pending out of {@code p}; fractions of a Crown or point stay for next time. */
    private static Batch snapshot(TradeProfile p) {
        Map<Trade, TradeRow> rows = new EnumMap<>(Trade.class);
        p.progress.forEach((t, pr) -> {
            if (pr.dirty) {
                rows.put(t, pr.row());
                pr.dirty = false;
            }
        });
        Map<Trade, Long> crowns = new EnumMap<>(Trade.class);
        p.pendingCrowns.replaceAll((t, amount) -> {
            long whole = (long) Math.floor(amount);
            if (whole > 0) {
                crowns.put(t, whole);
            }
            return amount - whole;
        });
        Map<LocalDate, Long> points = new HashMap<>();
        p.pendingPoints.replaceAll((day, amount) -> {
            long whole = (long) Math.floor(amount);
            if (whole > 0) {
                points.put(day, whole);
            }
            return amount - whole;
        });
        // Fractions from past days will never round up to anything; drop them.
        p.pendingPoints.keySet().removeIf(day -> !day.equals(p.day));
        return new Batch(p.id, rows, crowns, points);
    }

    private void write(Batch batch) throws SQLException {
        if (batch.empty()) {
            return;
        }
        repository.saveTrades(batch.player(), batch.rows());
        for (Map.Entry<LocalDate, Long> e : batch.points().entrySet()) {
            repository.addDailyPoints(batch.player(), e.getKey(), e.getValue());
        }
        ModeEconomy economy = core().economy();
        if (economy == null) {
            if (!batch.crowns().isEmpty() && !warnedNoEconomy) {
                warnedNoEconomy = true;
                plugin.getSLF4JLogger().warn("No Crowns economy on this server (server.gamemode isn't horizons): "
                        + "Trades earn XP but no Crowns");
            }
            return;
        }
        for (Map.Entry<Trade, Long> e : batch.crowns().entrySet()) {
            economy.deposit(batch.player(), e.getValue(), "trade:" + e.getKey().id());
        }
    }

    private void flushAll() {
        for (TradeProfile p : profiles.values()) {
            Batch batch = snapshot(p);
            if (!batch.empty()) {
                track(p.id, core().database().async(() -> {
                    write(batch);
                    return null;
                }));
            }
        }
    }

    private void track(UUID player, CompletableFuture<?> work) {
        CompletableFuture<?> logged = work.exceptionally(e -> {
            plugin.getSLF4JLogger().warn("Couldn't save Trades of {}: {}", player, e.getMessage());
            return null;
        });
        saving.merge(player, logged, (a, b) -> CompletableFuture.allOf(a, b));
        logged.whenComplete((r, e) -> saving.computeIfPresent(player, (k, f) -> f.isDone() ? null : f));
    }
}
