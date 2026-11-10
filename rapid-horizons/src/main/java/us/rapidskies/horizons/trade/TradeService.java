package us.rapidskies.horizons.trade;

import java.io.IOException;
import java.nio.file.Path;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EnchantingInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
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
 * Crowns, and so does the income modifier the Frontier sets (the Wildlands pay more).
 * Active Trades also give their side-grade perk ({@link TradePerks}).
 *
 * <p><b>Saving.</b> Progress, soft-cap points and Crowns go to the database in batches off
 * the main thread every {@code payout-seconds} and on quit, so a mob farm doesn't mean a
 * database write per kill. Each player's unsaved work waits in a {@link TradeOutbox}: a
 * batch the database refuses goes back and is tried again on the next tick, and every
 * payout has a ledger transaction id fixed when it was earned, so a retry never pays twice.
 * If the database is still down at shutdown, the rest is written to
 * {@value TradeJournal#FILE} and queued again on the next start.
 */
public final class TradeService implements Listener {

    public static final String NO_COOLDOWN = "rapid.horizons.trades.nocooldown";
    private static final Set<Material> REPLANTABLE = Set.of(Material.WHEAT, Material.CARROTS, Material.POTATOES,
            Material.BEETROOTS, Material.NETHER_WART);

    private final RapidHorizons plugin;
    private final HorizonsRepository repository;
    private final PlacedBlocks placed;
    private final Path journal;
    private final Map<UUID, TradeProfile> profiles = new ConcurrentHashMap<>();
    /** Unsaved work per player, kept after they leave until it's written. */
    private final Map<UUID, TradeOutbox> outboxes = new ConcurrentHashMap<>();
    /** Players whose outbox came from the journal: the file goes once they're all written. */
    private final Set<UUID> fromJournal = ConcurrentHashMap.newKeySet();
    /** Batch writes in flight per player: a quick rejoin waits for them before loading. */
    private final Map<UUID, CompletableFuture<?>> saving = new ConcurrentHashMap<>();
    private final AtomicLong payoutSeq = new AtomicLong();
    private final List<BiConsumer<Player, Block>> naturalBreakListeners = new CopyOnWriteArrayList<>();
    private final List<Predicate<Material>> watchedPlacements = new CopyOnWriteArrayList<>();
    private volatile ToDoubleFunction<Player> incomeModifier = p -> 1.0;
    /** The block a player just broke, if it grew there (valid through its BlockDropItemEvent). */
    private Block lastNaturalBreak;
    private BukkitTask payouts;
    private volatile boolean warnedNoEconomy;
    private volatile boolean failing;

    public TradeService(RapidHorizons plugin, HorizonsRepository repository) {
        this.plugin = plugin;
        this.repository = repository;
        this.placed = new PlacedBlocks(plugin);
        this.journal = plugin.getDataPath().resolve(TradeJournal.FILE);
    }

    private HorizonsSettings.Trades settings() {
        return plugin.settings().trades();
    }

    private TradeRules rules() {
        return settings().rules();
    }

    private TradePerks perks() {
        return settings().perks();
    }

    private RapidCore core() {
        return plugin.core();
    }

    public void start() {
        try {
            for (TradeOutbox.Batch b : TradeJournal.load(journal)) {
                outbox(b.player()).add(b);
                fromJournal.add(b.player());
            }
            if (!fromJournal.isEmpty()) {
                plugin.getSLF4JLogger().info("Queued unsaved Trade work of {} player(s) from {}", fromJournal.size(),
                        TradeJournal.FILE);
            }
        } catch (IOException e) {
            plugin.getSLF4JLogger().error("Couldn't read {} (left in place; fix or remove it): {}", journal,
                    e.getMessage());
        }
        long period = settings().payoutSeconds() * 20L;
        payouts = plugin.getServer().getScheduler().runTaskTimer(plugin, this::flushAll, period, period);
    }

    /**
     * Writes everything still pending. Blocks: for onDisable, while the database is still
     * open. What can't be written is kept in {@value TradeJournal#FILE}.
     */
    public void stop() {
        if (payouts != null) {
            payouts.cancel();
        }
        for (TradeProfile profile : List.copyOf(profiles.values())) {
            queue(profile);
        }
        saving.values().forEach(f -> f.exceptionally(e -> null).join());
        List<TradeOutbox.Batch> left = new ArrayList<>();
        for (TradeOutbox box : outboxes.values()) {
            TradeOutbox.Batch batch = box.take();
            if (batch == null) {
                continue;
            }
            TradeOutbox.Batch rest = write(batch);
            box.giveBack(rest);
            if (!box.idle()) {
                left.add(box.pending());
            }
        }
        try {
            TradeJournal.save(journal, left);
            if (!left.isEmpty()) {
                long crowns = left.stream().mapToLong(TradeOutbox.Batch::crowns).sum();
                plugin.getSLF4JLogger().error("The database is unreachable: kept unsaved Trade work of {} player(s) "
                        + "({} Crowns) in {}; it's paid on the next start", left.size(), crowns, journal);
            }
        } catch (IOException e) {
            plugin.getSLF4JLogger().error("Couldn't write {}; unsaved Trade work is lost: {}", journal, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ hooks for the rest of Horizons

    /** Called (main thread) when a player breaks a block that grew there, not one a player placed. */
    public void onNaturalBreak(BiConsumer<Player, Block> listener) {
        naturalBreakListeners.add(listener);
    }

    /** Also remember placements of these materials, so breaking them again isn't "natural". */
    public void watchPlacements(Predicate<Material> materials) {
        watchedPlacements.add(materials);
    }

    /** A multiplier on Trade XP and Crowns by where the player is (the Frontier's Wildlands). */
    public void setIncomeModifier(ToDoubleFunction<Player> modifier) {
        this.incomeModifier = modifier;
    }

    /** Whether {@code block} is the one a player just broke and it grew there; for BlockDropItemEvent. */
    public boolean brokeNaturally(Block block) {
        Block last = lastNaturalBreak;
        return last != null && last.equals(block);
    }

    // ------------------------------------------------------------------ reading, for the menu and claims

    /** Whether {@code player}'s Trades are loaded (they're online here). */
    public boolean loaded(UUID player) {
        return profiles.containsKey(player);
    }

    public record View(Trade trade, int level, double xp, double progress, boolean active, double perkPercent) {
    }

    /** {@code player}'s Trades for display, every Trade included. */
    public List<View> view(UUID player) {
        TradeProfile p = profiles.get(player);
        List<View> out = new ArrayList<>();
        for (Trade t : Trade.values()) {
            TradeProfile.Progress pr = p == null ? null : p.progress.get(t);
            double xp = pr == null ? 0 : pr.xp;
            int level = pr == null ? 0 : pr.level;
            out.add(new View(t, level, xp, rules().progress(xp, level), pr != null && pr.active,
                    perks().percent(t, level)));
        }
        return out;
    }

    public int activeCount(UUID player) {
        TradeProfile p = profiles.get(player);
        return p == null ? 0 : p.activeCount();
    }

    /** Sum of {@code player}'s Trade levels (0 when they aren't online here). */
    public int totalLevels(UUID player) {
        TradeProfile p = profiles.get(player);
        return p == null ? 0 : p.totalLevels();
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

    /** Extra auction house listings from Trade levels; 0 until the player's Trades are loaded. */
    public int marketListings(UUID player) {
        TradeProfile p = profiles.get(player);
        return p == null ? 0 : rules().marketListings(p.totalLevels());
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
        }).exceptionally(e -> {
            plugin.getSLF4JLogger().warn("Couldn't save the Trade switch time of {}: {}", player.getName(),
                    e.getMessage());
            return null;
        }));
        plugin.lang().send(player, "trades.dropped", Placeholder.component("trade", name),
                Placeholder.unparsed("hours", Integer.toString(settings().switchCooldownHours())));
    }

    public Component name(Trade trade) {
        return plugin.lang().get("trades.name." + trade.id());
    }

    // ------------------------------------------------------------------ earning

    /** Whether {@code player} works (survival or adventure, not AFK): rewards and perks need it. */
    private boolean working(Player player) {
        GameMode mode = player.getGameMode();
        return (mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE) && !core().afk().isAfk(player);
    }

    private void award(Player player, Trade trade, double baseXp) {
        if (baseXp <= 0) {
            return;
        }
        TradeProfile p = profiles.get(player.getUniqueId());
        if (p == null || !p.active(trade) || !working(player)) {
            return;
        }
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        p.rollDay(today);
        TradeProfile.Progress pr = p.of(trade);
        double multiplier = core().tailwind().multiplier(player) * Math.max(0, incomeModifier.applyAsDouble(player));
        TradeRules.Reward reward = rules().reward(baseXp, settings().source(trade).crownsPerXp(), pr.level,
                (long) p.pointsToday, multiplier);
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

    /** {@code trade}'s perk strength for {@code player} as a 0..1 fraction; 0 if it isn't active. */
    private double perk(Player player, Trade trade) {
        TradeProfile p = profiles.get(player.getUniqueId());
        if (p == null || !p.active(trade) || !working(player)) {
            return 0;
        }
        return perks().fraction(trade, p.of(trade).level);
    }

    private static boolean roll(double chance) {
        return chance > 0 && ThreadLocalRandom.current().nextDouble() < chance;
    }

    private boolean rewardedOnBreak(Block block) {
        String key = block.getType().getKey().getKey();
        return settings().source(Trade.MINER).xpFor(key) > 0
                || (!(block.getBlockData() instanceof Ageable) && settings().source(Trade.FARMER).xpFor(key) > 0);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        if (rewardedOnBreak(block) || watchedPlacements.stream().anyMatch(w -> w.test(block.getType()))) {
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
        lastNaturalBreak = wasPlaced ? null : block;
        if (wasPlaced) {
            return;
        }
        Player player = event.getPlayer();
        for (BiConsumer<Player, Block> listener : naturalBreakListeners) {
            listener.accept(player, block);
        }
        String key = block.getType().getKey().getKey();
        double minerXp = settings().source(Trade.MINER).xpFor(key);
        award(player, Trade.MINER, minerXp);
        if (minerXp > 0 && roll(perk(player, Trade.MINER))) {
            PotionEffect current = player.getPotionEffect(PotionEffectType.HASTE);
            if (current == null || (current.getAmplifier() == 0 && current.getDuration() < TradePerks.HASTE_SECONDS * 20)) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, TradePerks.HASTE_SECONDS * 20, 0,
                        true, false, true));
            }
        }
        if (block.getBlockData() instanceof Ageable crop && crop.getAge() < crop.getMaximumAge()) {
            return;
        }
        award(player, Trade.FARMER, settings().source(Trade.FARMER).xpFor(key));
    }

    /** Farmer perk: a ripe crop replants itself from one of its own seeds. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrops(BlockDropItemEvent event) {
        BlockState state = event.getBlockState();
        if (!REPLANTABLE.contains(state.getType()) || !(state.getBlockData() instanceof Ageable crop)
                || crop.getAge() < crop.getMaximumAge() || !roll(perk(event.getPlayer(), Trade.FARMER))) {
            return;
        }
        Material seed = switch (state.getType()) {
            case WHEAT -> Material.WHEAT_SEEDS;
            case CARROTS -> Material.CARROT;
            case POTATOES -> Material.POTATO;
            case BEETROOTS -> Material.BEETROOT_SEEDS;
            default -> Material.NETHER_WART;
        };
        for (Item item : event.getItems()) {
            ItemStack stack = item.getItemStack();
            if (stack.getType() == seed) {
                if (stack.getAmount() <= 1) {
                    item.remove();
                    event.getItems().remove(item);
                } else {
                    stack.setAmount(stack.getAmount() - 1);
                    item.setItemStack(stack);
                }
                Block block = event.getBlock();
                Material crops = state.getType();
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (block.getType().isAir()) {
                        BlockData fresh = crops.createBlockData();
                        if (fresh instanceof Ageable a) {
                            a.setAge(0);
                        }
                        block.setBlockData(fresh);
                    }
                });
                return;
            }
        }
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

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
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
        } else {
            // Hunter perk: more vanilla experience (not from spawners, so grinders gain nothing).
            double more = perk(killer, Trade.HUNTER);
            if (more > 0 && event.getDroppedExp() > 0) {
                event.setDroppedExp((int) Math.round(event.getDroppedExp() * (1 + more)));
            }
        }
        award(killer, Trade.HUNTER, xp);
    }

    /** Builder perk: less fall damage. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFall(EntityDamageEvent event) {
        if (event.getCause() == EntityDamageEvent.DamageCause.FALL && event.getEntity() instanceof Player player) {
            double less = perk(player, Trade.BUILDER);
            if (less > 0) {
                event.setDamage(event.getDamage() * (1 - less));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent event) {
        Player player = event.getEnchanter();
        award(player, Trade.ENCHANTER,
                event.getExpLevelCost() * settings().source(Trade.ENCHANTER).xpFor("level"));
        if (!roll(perk(player, Trade.ENCHANTER)) || !(event.getInventory() instanceof EnchantingInventory table)) {
            return;
        }
        // Enchanter perk: the lapis comes back (after vanilla has taken it).
        int lapis = event.whichButton() + 1;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            ItemStack current = table.getSecondary();
            if (current == null || current.getType().isAir()) {
                table.setSecondary(new ItemStack(Material.LAPIS_LAZULI, lapis));
            } else if (current.getType() == Material.LAPIS_LAZULI && current.getAmount() + lapis <= 64) {
                current.setAmount(current.getAmount() + lapis);
                table.setSecondary(current);
            } else {
                player.getInventory().addItem(new ItemStack(Material.LAPIS_LAZULI, lapis)).values()
                        .forEach(rest -> player.getWorld().dropItemNaturally(player.getLocation(), rest));
            }
            plugin.lang().send(player, "trades.perk-used.enchanter");
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() == PlayerFishEvent.State.FISHING) {
            // Fisher perk: shorter waits for a bite.
            double quicker = perk(event.getPlayer(), Trade.FISHER);
            if (quicker > 0) {
                var hook = event.getHook();
                int min = TradePerks.shortenedWait(hook.getMinWaitTime(), quicker);
                int max = Math.max(min, TradePerks.shortenedWait(hook.getMaxWaitTime(), quicker));
                hook.setWaitTime(min, max);
            }
            return;
        }
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH || !(event.getCaught() instanceof Item item)) {
            return;
        }
        ItemStack stack = item.getItemStack();
        String group = FishCatch.classify(stack.getType().name(), !stack.getEnchantments().isEmpty()
                || stack.getType().name().equals("ENCHANTED_BOOK"));
        award(event.getPlayer(), Trade.FISHER, settings().source(Trade.FISHER).xpFor(group));
    }

    // ------------------------------------------------------------------ loading and saving

    private TradeOutbox outbox(UUID player) {
        return outboxes.computeIfAbsent(player, TradeOutbox::new);
    }

    // MONITOR: only logins that go ahead. Async thread, so blocking is fine.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        UUID id = event.getUniqueId();
        CompletableFuture<?> pending = saving.get(id);
        if (pending != null) {
            pending.exceptionally(e -> null).join();
        }
        TradeOutbox box = outbox(id);
        try {
            box.pause();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            // Work still waiting to be saved (the database was down when they left) is newer
            // than what the database holds.
            TradeOutbox.Batch unsaved = box.pending();
            Map<Trade, TradeRow> rows = new EnumMap<>(Trade.class);
            rows.putAll(repository.trades(id));
            rows.putAll(unsaved.rows());
            HorizonsRepository.PlayerRow row = repository.player(id);
            long points = repository.dailyPoints(id, today) + unsaved.points().getOrDefault(today, 0L);
            profiles.put(id, new TradeProfile(id, rows, row.tradesChangedAt().orElse(null), today, points, rules()));
        } catch (SQLException e) {
            // Without a profile they simply earn nothing this session; nothing is lost.
            plugin.getSLF4JLogger().warn("Couldn't load Trades of {}: {}", event.getName(), e.getMessage());
        } finally {
            box.resume();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        TradeProfile p = profiles.remove(event.getPlayer().getUniqueId());
        if (p != null) {
            queue(p);
            flush(p.id);
        }
    }

    /** Moves what's pending in {@code p} into its outbox; fractions of a Crown or point stay for next time. */
    private void queue(TradeProfile p) {
        Map<Trade, TradeRow> rows = new EnumMap<>(Trade.class);
        p.progress.forEach((t, pr) -> {
            if (pr.dirty) {
                rows.put(t, pr.row());
                pr.dirty = false;
            }
        });
        List<TradeOutbox.Payout> payouts = new ArrayList<>();
        long now = System.currentTimeMillis();
        p.pendingCrowns.replaceAll((t, amount) -> {
            long whole = (long) Math.floor(amount);
            if (whole > 0) {
                payouts.add(new TradeOutbox.Payout(t, whole, txnId(p.id, t, now)));
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
        if (!rows.isEmpty() || !payouts.isEmpty() || !points.isEmpty()) {
            outbox(p.id).add(rows, points, payouts);
        }
    }

    /** Unique per payout, fixed when earned: {@code trade:<uuid>:<trade>:<millis>:<n>} (at most 128 chars). */
    private String txnId(UUID player, Trade trade, long millis) {
        return "trade:" + player + ":" + trade.id() + ":" + millis + ":" + payoutSeq.incrementAndGet();
    }

    /**
     * Writes {@code batch} as far as the database lets it and returns what's left: rows
     * first (absolute, so rewriting them is harmless), then points, then each payout, which
     * its transaction id keeps from being paid twice. Blocks.
     */
    private TradeOutbox.Batch write(TradeOutbox.Batch batch) {
        UUID player = batch.player();
        try {
            repository.saveTrades(player, batch.rows());
        } catch (SQLException e) {
            failed(batch, e);
            return batch;
        }
        Map<LocalDate, Long> points = new HashMap<>(batch.points());
        for (Map.Entry<LocalDate, Long> e : batch.points().entrySet()) {
            try {
                repository.addDailyPoints(player, e.getKey(), e.getValue());
                points.remove(e.getKey());
            } catch (SQLException ex) {
                TradeOutbox.Batch rest = new TradeOutbox.Batch(player, Map.of(), points, batch.payouts());
                failed(rest, ex);
                return rest;
            }
        }
        ModeEconomy economy = core().economy();
        if (economy == null) {
            if (!batch.payouts().isEmpty() && !warnedNoEconomy) {
                warnedNoEconomy = true;
                plugin.getSLF4JLogger().warn("No Crowns economy on this server (server.gamemode isn't horizons): "
                        + "Trades earn XP but no Crowns");
            }
            return TradeOutbox.Batch.empty(player);
        }
        List<TradeOutbox.Payout> payouts = batch.payouts();
        for (int i = 0; i < payouts.size(); i++) {
            TradeOutbox.Payout p = payouts.get(i);
            try {
                // Applied, or a duplicate (it reached the ledger on an earlier try): paid either way.
                economy.deposit(player, p.crowns(), "trade:" + p.trade().id(), p.txnId());
            } catch (SQLException | RuntimeException ex) {
                TradeOutbox.Batch rest = new TradeOutbox.Batch(player, Map.of(), Map.of(),
                        payouts.subList(i, payouts.size()));
                failed(rest, ex);
                return rest;
            }
        }
        if (failing) {
            failing = false;
            plugin.getSLF4JLogger().info("Trade saving works again; held payouts are being paid");
        }
        return TradeOutbox.Batch.empty(player);
    }

    private void failed(TradeOutbox.Batch held, Exception e) {
        if (!failing) {
            failing = true;
            plugin.getSLF4JLogger().warn("Couldn't save Trades of {} ({}); holding {} Crowns and retrying every {} s",
                    held.player(), e.getMessage(), held.crowns(), settings().payoutSeconds());
        }
    }

    /** Starts one write of {@code player}'s outbox off the main thread, unless one is already out. */
    private void flush(UUID player) {
        TradeOutbox box = outboxes.get(player);
        if (box == null) {
            return;
        }
        TradeOutbox.Batch batch = box.take();
        if (batch == null) {
            forgetIfDone(box);
            return;
        }
        CompletableFuture<TradeOutbox.Batch> work = core().database().async(() -> write(batch));
        track(player, work.handle((rest, e) -> {
            box.giveBack(rest != null ? rest : batch);
            forgetIfDone(box);
            return null;
        }));
    }

    /** Drops an emptied outbox of a player who isn't online, and the journal once it's all paid. */
    private void forgetIfDone(TradeOutbox box) {
        UUID id = box.player();
        if (box.idle() && !profiles.containsKey(id)) {
            outboxes.remove(id, box);
        }
        if (box.idle() && fromJournal.remove(id) && fromJournal.isEmpty()) {
            try {
                java.nio.file.Files.deleteIfExists(journal);
            } catch (IOException e) {
                plugin.getSLF4JLogger().warn("Couldn't remove {}: {}", journal, e.getMessage());
            }
        }
    }

    private void flushAll() {
        for (TradeProfile p : profiles.values()) {
            queue(p);
        }
        for (UUID id : List.copyOf(outboxes.keySet())) {
            flush(id);
        }
    }

    private void track(UUID player, CompletableFuture<?> work) {
        saving.merge(player, work, (a, b) -> CompletableFuture.allOf(a, b));
        work.whenComplete((r, e) -> saving.computeIfPresent(player, (k, f) -> f.isDone() ? null : f));
    }

    /** Crowns earned but not yet in the ledger, over everyone (for {@code /horizons status}). */
    public long heldCrowns() {
        return outboxes.values().stream().mapToLong(b -> b.pending().crowns()).sum();
    }

    /** For the Wildlands' richer ores: only ores a Miner would be paid for count. */
    public boolean isOre(Material material) {
        return settings().source(Trade.MINER).xpFor(material.getKey().getKey()) > 0;
    }

    /** Used by perks and Chapters: the level of {@code trade} for an online player (0 otherwise). */
    public int level(UUID player, Trade trade) {
        TradeProfile p = profiles.get(player);
        TradeProfile.Progress pr = p == null ? null : p.progress.get(trade);
        return pr == null ? 0 : pr.level;
    }

}
