package us.rapidskies.skyrealms.tier;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import us.rapidskies.skyrealms.Give;
import us.rapidskies.skyrealms.RapidSkyRealms;
import us.rapidskies.skyrealms.SkyRealmsSettings;
import us.rapidskies.skyrealms.SkyRealmsSettings.Tier;
import us.rapidskies.skyrealms.data.SkyRealmsRepository;
import us.rapidskies.skyrealms.data.SkyRealmsRepository.Progress;
import us.rapidskies.skyrealms.island.Islands.IslandRef;

/**
 * Realm Tiers (PLAN.md §5.1): every island is on a tier (Meadow → Desert → Frost) and works on
 * the next one's goals; once they're all done, its leader ascends it for the tier's cost.
 * A tier sets the island's generator, unlocks its biome and shop categories, and gives its
 * reward items once.
 *
 * <p>Tiers and progress live in memory (loaded at start) and are the truth on this server.
 * Progress is counted here and written in batches every {@value #FLUSH_SECONDS} s and at
 * shutdown; a crash loses at most that much counting. Ascending is a database transaction
 * (tier and payment together), so it can't happen twice or for free.
 */
public final class TierService implements Listener {

    private static final int FLUSH_SECONDS = 30;
    private static final Set<Material> STACKING = EnumSet.of(Material.CACTUS, Material.SUGAR_CANE, Material.BAMBOO,
            Material.KELP_PLANT, Material.KELP);
    private static final Set<Material> UNGROWN_HARVEST = EnumSet.of(Material.MELON, Material.PUMPKIN,
            Material.CACTUS, Material.SUGAR_CANE, Material.BAMBOO, Material.KELP_PLANT, Material.KELP,
            Material.BROWN_MUSHROOM, Material.RED_MUSHROOM);

    private final RapidSkyRealms plugin;
    private final Map<UUID, String> tierOf = new ConcurrentHashMap<>();
    /** Progress toward each island's next tier: island → goal id → count. */
    private final Map<UUID, Map<String, Long>> progress = new ConcurrentHashMap<>();
    /** Counted but not yet written: island → tier → goal → delta. */
    private final Map<UUID, Map<String, Map<String, Long>>> pending = new ConcurrentHashMap<>();
    private final Set<UUID> toldReady = ConcurrentHashMap.newKeySet();
    private final Set<UUID> ascending = ConcurrentHashMap.newKeySet();
    private final BlockMarks generated = new BlockMarks(200_000);
    private final BlockMarks placed = new BlockMarks(500_000);
    private volatile boolean loaded;
    private BukkitTask flushTask;

    public TierService(RapidSkyRealms plugin) {
        this.plugin = plugin;
    }

    /** Loads every island's tier and progress (blocking: called once while enabling). */
    public void start() throws SQLException {
        SkyRealmsRepository repo = plugin.repo();
        tierOf.putAll(repo.tiers());
        Map<UUID, String> nextOf = new HashMap<>();
        for (Map.Entry<UUID, String> e : tierOf.entrySet()) {
            tierFor(e.getKey()).flatMap(t -> settings().next(t)).ifPresent(n -> nextOf.put(e.getKey(), n.id()));
        }
        String firstNext = settings().next(settings().firstTier()).map(Tier::id).orElse(null);
        for (Progress p : repo.progress()) {
            String next = nextOf.getOrDefault(p.island(), tierOf.containsKey(p.island()) ? null : firstNext);
            if (p.tier().equals(next)) {
                progress.computeIfAbsent(p.island(), k -> new ConcurrentHashMap<>()).put(p.goal(), p.progress());
            }
        }
        loaded = true;
        flushTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::flushQuietly,
                FLUSH_SECONDS * 20L, FLUSH_SECONDS * 20L);
        plugin.getSLF4JLogger().info("Realm Tiers loaded for {} island(s)", tierOf.size());
    }

    /** Writes what's left (blocking; onDisable, while the database is open). */
    public void stop() {
        if (flushTask != null) {
            flushTask.cancel();
        }
        flushQuietly();
    }

    private SkyRealmsSettings settings() {
        return plugin.settings();
    }

    // ------------------------------------------------------------------ reading

    /** The island's tier (the first for islands without a row). */
    public Tier tier(UUID island) {
        return tierFor(island).orElse(settings().firstTier());
    }

    private Optional<Tier> tierFor(UUID island) {
        String id = tierOf.get(island);
        return id == null ? Optional.empty() : settings().tier(id);
    }

    /** The tier of {@code player}'s island; the first tier without one. */
    public Tier tierOfPlayer(Player player) {
        return plugin.islands().of(player).map(i -> tier(i.id())).orElse(settings().firstTier());
    }

    public Optional<Tier> next(UUID island) {
        return settings().next(tier(island));
    }

    /** Progress toward a goal of the next tier, never above its target. */
    public long progress(UUID island, TierGoal goal) {
        if (goal.type() == TierGoal.Type.LEVEL) {
            BigDecimal level = plugin.islands().level(island);
            return goal.clamp(level.longValue());
        }
        return goal.clamp(progress.getOrDefault(island, Map.of()).getOrDefault(goal.id(), 0L));
    }

    public boolean ready(UUID island) {
        return next(island).map(n -> n.requires().stream().allMatch(g -> g.done(progress(island, g))))
                .orElse(false);
    }

    /** Biomes the island may pick: those of its tier and every tier below. */
    public List<String> biomes(UUID island) {
        int index = tier(island).index();
        return settings().tiers().stream().filter(t -> t.index() <= index).map(Tier::biome).distinct().toList();
    }

    /** Whether a shop category (or anything gated by tier id) is open at {@code tierIndex}. */
    public boolean unlocked(String tierId, Tier current) {
        return settings().tier(tierId).map(t -> t.index() <= current.index()).orElse(false);
    }

    // ------------------------------------------------------------------ counting

    /**
     * Counts {@code amount} toward the island's next tier, for every goal of {@code type} about
     * {@code material} (null for SELL, FISH, EXPEDITION, ROUTE). Any thread.
     */
    public void record(UUID island, TierGoal.Type type, Material material, long amount) {
        if (!loaded || amount <= 0) {
            return;
        }
        Optional<Tier> next = next(island);
        if (next.isEmpty()) {
            return;
        }
        boolean counted = false;
        for (TierGoal g : next.get().requires()) {
            if (g.counts(type, material)) {
                progress.computeIfAbsent(island, k -> new ConcurrentHashMap<>()).merge(g.id(), amount, Long::sum);
                pending.computeIfAbsent(island, k -> new ConcurrentHashMap<>())
                        .computeIfAbsent(next.get().id(), k -> new ConcurrentHashMap<>())
                        .merge(g.id(), amount, Long::sum);
                counted = true;
            }
        }
        if (counted) {
            Bukkit.getScheduler().runTask(plugin, () -> tellIfReady(island));
        }
    }

    /** Tells the island's online members once that everything is done (main thread). */
    public void tellIfReady(UUID island) {
        if (toldReady.contains(island) || !ready(island)) {
            return;
        }
        toldReady.add(island);
        Tier next = next(island).orElseThrow();
        for (Player p : plugin.islands().onlineMembers(island)) {
            plugin.lang().send(p, "realm.ready", Placeholder.component("tier", name(next)));
        }
    }

    private void flushQuietly() {
        List<Progress> deltas = new ArrayList<>();
        for (UUID island : List.copyOf(pending.keySet())) {
            Map<String, Map<String, Long>> byTier = pending.remove(island);
            if (byTier == null) {
                continue;
            }
            byTier.forEach((tier, goals) -> goals.forEach((goal, d) -> deltas.add(new Progress(island, tier, goal, d))));
        }
        if (deltas.isEmpty()) {
            return;
        }
        try {
            plugin.repo().addProgress(deltas);
        } catch (SQLException | RuntimeException e) {
            plugin.getSLF4JLogger().warn("Couldn't save Realm Tier progress ({} rows; retried next time): {}",
                    deltas.size(), e.getMessage());
            for (Progress d : deltas) {
                pending.computeIfAbsent(d.island(), k -> new ConcurrentHashMap<>())
                        .computeIfAbsent(d.tier(), k -> new ConcurrentHashMap<>())
                        .merge(d.goal(), d.progress(), Long::sum);
            }
        }
    }

    // ------------------------------------------------------------------ islands coming and going

    /** A new island: first tier, its generator once the layout is pasted. Main thread. */
    public void islandCreated(UUID island) {
        String first = settings().firstTier().id();
        tierOf.put(island, first);
        progress.remove(island);
        toldReady.remove(island);
        plugin.async(() -> {
            plugin.repo().setTier(island, first, Instant.now());
            return null;
        }, v -> { });
        // SuperiorSkyblock2 sets up the island's values after the create event; apply after it.
        Bukkit.getScheduler().runTaskLater(plugin, () -> applyGenerator(island), 40L);
    }

    /** A disbanded island: forgotten here and in the database. */
    public void islandDisbanded(UUID island) {
        tierOf.remove(island);
        progress.remove(island);
        pending.remove(island);
        toldReady.remove(island);
        plugin.async(() -> {
            plugin.repo().forgetIsland(island);
            return null;
        }, v -> { });
    }

    /** Makes the island's generator match its tier. Main thread. */
    public void applyGenerator(UUID island) {
        plugin.islands().applyGenerator(island, tier(island).generator());
    }

    /** After a reload: every island with someone online gets its tier's generator again. */
    public void reapplyOnline() {
        Set<UUID> done = new java.util.HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            plugin.islands().of(p).map(IslandRef::id).filter(done::add).ifPresent(this::applyGenerator);
        }
    }

    // ------------------------------------------------------------------ ascending

    /** The island leader (or a manager) moves the island up a tier. Main thread. */
    public void ascend(Player player) {
        Optional<IslandRef> island = plugin.islands().of(player);
        if (island.isEmpty()) {
            plugin.lang().send(player, "errors.no-island");
            return;
        }
        UUID id = island.get().id();
        Tier from = tier(id);
        Optional<Tier> to = settings().next(from);
        if (to.isEmpty()) {
            plugin.lang().send(player, "realm.top");
            return;
        }
        if (!plugin.islands().canManage(player, id, settings().manageRoleWeight())) {
            plugin.lang().send(player, "errors.not-manager");
            return;
        }
        if (!ready(id)) {
            plugin.lang().send(player, "realm.not-ready", Placeholder.component("tier", name(to.get())));
            return;
        }
        if (!ascending.add(id)) {
            return;
        }
        Tier target = to.get();
        String txn = "realm:" + id + ":" + target.id();
        plugin.async(() -> plugin.repo().ascend(plugin.core().modeLedger(us.rapidskies.common.economy.EconomyMode.SKYREALMS),
                id, from.id(), target.id(), Instant.now(), player.getUniqueId(), target.cost(),
                plugin.core().settings().serverId(), txn), result -> {
            ascending.remove(id);
            if (plugin.core().economy() != null) {
                plugin.core().economy().changed(player.getUniqueId(), result.balance());
            }
            switch (result.outcome()) {
                case NOT_CURRENT -> plugin.lang().send(player, "realm.already");
                case INSUFFICIENT -> plugin.lang().send(player, "realm.cant-afford",
                        Placeholder.unparsed("cost", String.format("%,d", target.cost())));
                case OK -> ascended(player, island.get(), target);
            }
        }, player, () -> ascending.remove(id));
    }

    private void ascended(Player player, IslandRef island, Tier target) {
        tierOf.put(island.id(), target.id());
        progress.remove(island.id());
        toldReady.remove(island.id());
        applyGenerator(island.id());
        List<ItemStack> rewards = new ArrayList<>();
        for (SkyRealmsSettings.ItemAmount a : target.rewards()) {
            if (a.max() > 0) {
                rewards.add(ItemStack.of(a.material(), a.max()));
            }
        }
        Give.items(player, rewards);
        Component tierName = name(target);
        for (Player p : plugin.islands().onlineMembers(island.id())) {
            plugin.lang().send(p, "realm.ascended", Placeholder.component("tier", tierName),
                    Placeholder.unparsed("player", player.getName()));
        }
        plugin.announcer().local(plugin.lang().get("realm.announce", Placeholder.unparsed("island", island.name()),
                Placeholder.component("tier", tierName)));
        plugin.getSLF4JLogger().info("Island {} ({}) ascended to {} (paid by {})", island.name(), island.id(),
                target.id(), player.getName());
    }

    /** Staff: puts an island on a tier without goals or cost. Main thread. */
    public void setTier(IslandRef island, Tier tier, Runnable done) {
        plugin.async(() -> {
            plugin.repo().setTier(island.id(), tier.id(), Instant.now());
            return null;
        }, v -> {
            tierOf.put(island.id(), tier.id());
            progress.remove(island.id());
            toldReady.remove(island.id());
            applyGenerator(island.id());
            done.run();
        });
    }

    public Component name(Tier tier) {
        return plugin.lang().get("realm.tier." + tier.id() + ".name");
    }

    // ------------------------------------------------------------------ events

    /** A block the island generator made: mining it counts (from SuperiorSkyblock2's generator event too). */
    public void markGenerated(Block block) {
        generated.mark(block);
    }

    // Vanilla's lava + water forming (SuperiorSkyblock2 then picks the block); MONITOR: only if it happened.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onForm(BlockFormEvent event) {
        if (plugin.islands().isIslandWorld(event.getBlock().getWorld())) {
            Material m = event.getNewState().getType();
            if (m == Material.COBBLESTONE || m == Material.STONE || m == Material.BASALT) {
                generated.mark(event.getBlock());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block b = event.getBlockPlaced();
        generated.take(b);
        if (UNGROWN_HARVEST.contains(b.getType()) && plugin.islands().isIslandWorld(b.getWorld())) {
            placed.mark(b);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block b = event.getBlock();
        if (!plugin.islands().isIslandWorld(b.getWorld())) {
            return;
        }
        Player player = event.getPlayer();
        Optional<UUID> island = memberIslandAt(player, b);
        Material type = b.getType();
        if (generated.take(b)) {
            island.ifPresent(id -> record(id, TierGoal.Type.MINE, type, 1));
            return;
        }
        boolean wasPlaced = placed.take(b);
        if (island.isEmpty()) {
            return;
        }
        if (b.getBlockData() instanceof Ageable crop && !STACKING.contains(type)) {
            if (crop.getAge() >= crop.getMaximumAge()) {
                record(island.get(), TierGoal.Type.HARVEST, cropItem(type), 1);
            }
        } else if (UNGROWN_HARVEST.contains(type) && !wasPlaced) {
            long n = 1;
            if (STACKING.contains(type)) {
                // Breaking a stalk takes everything above it with it.
                for (Block up = b.getRelative(0, 1, 0); up.getType() == type && n < 32; up = up.getRelative(0, 1, 0)) {
                    if (!placed.take(up)) {
                        n++;
                    }
                }
            }
            record(island.get(), TierGoal.Type.HARVEST, cropItem(type), n);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() == PlayerFishEvent.State.CAUGHT_FISH) {
            memberIslandAt(event.getPlayer(), event.getPlayer().getLocation().getBlock())
                    .ifPresent(id -> record(id, TierGoal.Type.FISH, null, 1));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        // SuperiorSkyblock2 loads the player's island data on join; a tick later it's there.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) {
                plugin.islands().of(p).ifPresent(i -> {
                    applyGenerator(i.id());
                    tellIfReady(i.id());
                });
            }
        }, 20L);
    }

    /** The island at the block, if the player is one of its members. */
    private Optional<UUID> memberIslandAt(Player player, Block block) {
        Optional<IslandRef> here = plugin.islands().at(block.getLocation());
        Optional<IslandRef> own = plugin.islands().of(player);
        if (here.isPresent() && own.isPresent() && here.get().id().equals(own.get().id())) {
            return Optional.of(own.get().id());
        }
        return Optional.empty();
    }

    /** A crop block as the item players know it by (carrots → carrot). */
    static Material cropItem(Material block) {
        return switch (block) {
            case CARROTS -> Material.CARROT;
            case POTATOES -> Material.POTATO;
            case BEETROOTS -> Material.BEETROOT;
            case COCOA -> Material.COCOA_BEANS;
            case KELP_PLANT -> Material.KELP;
            case SWEET_BERRY_BUSH -> Material.SWEET_BERRIES;
            default -> block;
        };
    }
}
