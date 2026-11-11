package us.rapidskies.skyrealms.expedition;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import us.rapidskies.core.economy.ModeEconomy;
import us.rapidskies.skyrealms.Give;
import us.rapidskies.skyrealms.RapidSkyRealms;
import us.rapidskies.skyrealms.SkyRealmsSettings;
import us.rapidskies.skyrealms.SkyRealmsSettings.ItemAmount;
import us.rapidskies.skyrealms.island.Blueprint;
import us.rapidskies.skyrealms.tier.TierGoal;

/**
 * Expeditions (PLAN.md §5.1): every few hours a themed resource island drifts into the shared
 * Expedition world for a while, announced network-wide a few minutes ahead.
 *
 * <ul>
 *   <li>Only its resource blocks can be broken, and what they give goes straight into the
 *       miner's inventory (nothing lies on the ground to be taken), with contribution points.</li>
 *   <li>Its heart takes many hits from everyone; when it breaks, each striker gets the heart's
 *       loot in proportion to their hits ({@link Shares#heart}), so the last hit means nothing.</li>
 *   <li>When it drifts away (time's up, or everything's harvested), every contributor is paid
 *       Coins for their points (Tailwind applies) and Bolts, and their island counts an
 *       Expedition toward its next Realm Tier.</li>
 * </ul>
 *
 * <p>The island's theme, seed and position are journalled ({@value #JOURNAL}) before it's
 * built, so a crash mid-Expedition never leaves it behind: the next start builds the same
 * blueprint again as air. Nothing can be placed in the Expedition world, so removing the
 * blueprint's blocks removes everything.
 */
public final class ExpeditionService implements Listener {

    static final String JOURNAL = "expedition-journal.txt";
    private static final int BLOCKS_PER_TICK = 2500;
    private static final int FALL_DEPTH = 30;

    /** Where the Expedition cycle is. */
    public enum Phase { IDLE, WARNED, BUILDING, ACTIVE, LEAVING }

    /** The Expedition that's here (or arriving, or leaving). */
    private static final class Run {
        final Theme theme;
        final long seed;
        final ExpeditionLayouts.Layout layout;
        final World world;
        final int ox;
        final int oy;
        final int oz;
        final Set<Long> nodes = new HashSet<>();
        final Map<UUID, Integer> points = new HashMap<>();
        final Map<UUID, Integer> hits = new HashMap<>();
        final BossBar bar;
        long dbId = -1;
        Instant endsAt = Instant.MAX;
        int needed;
        boolean heartBroken;
        boolean warnedLeaving;
        HeartMarker marker;

        Run(Theme theme, long seed, World world, int ox, int oy, int oz, BossBar bar) {
            this.theme = theme;
            this.seed = seed;
            this.layout = ExpeditionLayouts.build(theme, seed);
            this.world = world;
            this.ox = ox;
            this.oy = oy;
            this.oz = oz;
            this.bar = bar;
        }

        static long rel(int x, int y, int z) {
            return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
        }

        Location pad() {
            return new Location(world, ox + layout.padX(), oy + layout.padY(), oz + layout.padZ(), layout.padYaw(), 0);
        }

        int struck() {
            return hits.values().stream().mapToInt(Integer::intValue).sum();
        }

        /** Takes the heart's glow and label away (it broke, or the Expedition is leaving). */
        void unmark() {
            if (marker != null) {
                marker.remove();
                marker = null;
            }
        }

        String txnBase() {
            return "expedition:" + (dbId > 0 ? Long.toString(dbId) : "s" + seed);
        }
    }

    private final RapidSkyRealms plugin;
    private final Path journal;
    private Phase phase = Phase.IDLE;
    private Instant nextAt = Instant.MAX;
    private Theme nextTheme;
    private int rotation;
    private Run run;
    private BukkitTask ticker;

    public ExpeditionService(RapidSkyRealms plugin) {
        this.plugin = plugin;
        this.journal = plugin.getDataPath().resolve(JOURNAL);
    }

    private SkyRealmsSettings.Expeditions cfg() {
        return plugin.settings().expeditions();
    }

    /** Clears a crash leftover, picks up the rotation and schedules the next one. Main thread, while enabling. */
    public void start() throws SQLException {
        rotation = (int) Math.min(Integer.MAX_VALUE, plugin.repo().expeditionCount());
        recoverJournal();
        schedule(Instant.now());
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    /** Ends what's here at once (plugin disabling): pays out, removes the island synchronously. */
    public void stop() {
        if (ticker != null) {
            ticker.cancel();
        }
        if (run != null && (phase == Phase.ACTIVE || phase == Phase.BUILDING || phase == Phase.LEAVING)) {
            if (phase == Phase.ACTIVE) {
                payOut(run);
            }
            sendAway(run.world);
            run.unmark();
            for (Blueprint.Block b : run.layout.blueprint().blocks()) {
                run.world.getBlockAt(run.ox + b.x(), run.oy + b.y(), run.oz + b.z()).setType(Material.AIR, false);
            }
            hideBar(run);
            deleteJournal();
            run = null;
        }
        phase = Phase.IDLE;
    }

    private void schedule(Instant from) {
        int jitter = cfg().jitterMinutes();
        int minutes = cfg().intervalMinutes() + (jitter == 0 ? 0 : ThreadLocalRandom.current().nextInt(-jitter, jitter + 1));
        nextAt = from.plus(Duration.ofMinutes(Math.max(1, minutes)));
        List<Theme> themes = cfg().themes();
        nextTheme = themes.get(Math.floorMod(rotation, themes.size()));
        phase = Phase.IDLE;
    }

    // ------------------------------------------------------------------ status, for commands and placeholders

    public Phase phase() {
        return phase;
    }

    public boolean active() {
        return phase == Phase.ACTIVE && run != null;
    }

    /** The theme coming next, or the one that's here. */
    public Theme theme() {
        return run != null && phase != Phase.IDLE && phase != Phase.WARNED ? run.theme : nextTheme;
    }

    /** Until the next one arrives (IDLE, WARNED) or this one leaves (ACTIVE). */
    public Duration remaining() {
        Instant target = active() ? run.endsAt : nextAt;
        return Duration.between(Instant.now(), target).isNegative() ? Duration.ZERO
                : Duration.between(Instant.now(), target);
    }

    public World world() {
        NamespacedKey key = NamespacedKey.fromString(cfg().world());
        return key == null ? null : Bukkit.getWorld(key);
    }

    public boolean inWorld(World w) {
        World ours = world();
        return ours != null && ours.equals(w);
    }

    // ------------------------------------------------------------------ the clock

    private void tick() {
        Instant now = Instant.now();
        switch (phase) {
            case IDLE -> {
                if (!now.isBefore(nextAt.minus(Duration.ofMinutes(cfg().warningMinutes())))) {
                    if (Bukkit.getOnlinePlayers().size() >= cfg().minPlayers()) {
                        phase = Phase.WARNED;
                        plugin.announcer().network(plugin.lang().get("expedition.warning",
                                Placeholder.component("theme", themeName(nextTheme)),
                                Placeholder.unparsed("minutes", Long.toString(Math.max(1,
                                        Duration.between(now, nextAt).toMinutes())))));
                    } else {
                        schedule(now);
                    }
                }
            }
            case WARNED -> {
                if (!now.isBefore(nextAt)) {
                    arrive(nextTheme);
                }
            }
            case ACTIVE -> activeTick(now);
            default -> {
            }
        }
    }

    /** Staff: brings an Expedition now, of {@code theme} or the next in the rotation. */
    public boolean startNow(Theme theme) {
        if (phase != Phase.IDLE && phase != Phase.WARNED) {
            return false;
        }
        arrive(theme == null ? nextTheme : theme);
        return true;
    }

    /** Staff: sends the one that's here away now (contributors are paid). */
    public boolean stopNow() {
        if (!active()) {
            return false;
        }
        depart();
        return true;
    }

    // ------------------------------------------------------------------ arriving

    private void arrive(Theme theme) {
        World world = world();
        if (world == null) {
            plugin.getSLF4JLogger().error("The Expedition world {} isn't loaded (rapid-core worlds config); "
                    + "skipping this Expedition", cfg().world());
            schedule(Instant.now());
            return;
        }
        int slot = Math.floorMod(rotation, 8);
        BossBar bar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.PURPLE, BossBar.Overlay.NOTCHED_10);
        Run r = new Run(theme, ThreadLocalRandom.current().nextLong(), world, slot * cfg().spacing(), cfg().height(),
                0, bar);
        run = r;
        phase = Phase.BUILDING;
        writeJournal(r);
        List<Blueprint.Block> blocks = new ArrayList<>(r.layout.blueprint().blocks());
        blocks.sort(Comparator.comparingInt(Blueprint.Block::y)); // drifts in from below
        loadChunks(r).thenRun(() -> Bukkit.getScheduler().runTask(plugin, () -> place(r, blocks, 0, () -> activate(r))));
    }

    private CompletableFuture<Void> loadChunks(Run r) {
        int[] b = r.layout.blueprint().bounds();
        List<CompletableFuture<?>> loads = new ArrayList<>();
        for (int cx = (r.ox + b[0]) >> 4; cx <= (r.ox + b[3]) >> 4; cx++) {
            for (int cz = (r.oz + b[2]) >> 4; cz <= (r.oz + b[5]) >> 4; cz++) {
                loads.add(r.world.getChunkAtAsync(cx, cz));
            }
        }
        return CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new));
    }

    private void place(Run r, List<Blueprint.Block> blocks, int from, Runnable done) {
        if (run != r) {
            return; // stopped meanwhile
        }
        int to = Math.min(blocks.size(), from + BLOCKS_PER_TICK);
        for (int i = from; i < to; i++) {
            Blueprint.Block b = blocks.get(i);
            r.world.getBlockAt(r.ox + b.x(), r.oy + b.y(), r.oz + b.z())
                    .setBlockData(Bukkit.createBlockData(b.data()), false);
        }
        if (to < blocks.size()) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> place(r, blocks, to, done), 1L);
        } else {
            done.run();
        }
    }

    private void activate(Run r) {
        for (Blueprint.Block b : r.layout.blueprint().blocks()) {
            if (r.layout.node(b)) {
                r.nodes.add(Run.rel(b.x(), b.y(), b.z()));
            }
        }
        r.endsAt = Instant.now().plus(Duration.ofMinutes(cfg().durationMinutes()));
        r.needed = Shares.hitsNeeded(cfg().hitsPerPlayer(), cfg().minHits(), r.world.getPlayers().size());
        phase = Phase.ACTIVE;
        rotation++;
        r.marker = new HeartMarker(plugin, r.world, r.ox + r.layout.heartX(), r.oy + r.layout.heartY(),
                r.oz + r.layout.heartZ(), r.theme.heart(), Color.fromRGB(r.theme.glow()));
        r.marker.show(heartLabel(r));
        plugin.announcer().network(plugin.lang().get("expedition.arrived",
                Placeholder.component("theme", themeName(r.theme)),
                Placeholder.unparsed("minutes", Integer.toString(cfg().durationMinutes()))));
        plugin.async(() -> plugin.repo().startExpedition(r.theme.id(), Instant.now()), id -> r.dbId = id);
        plugin.getSLF4JLogger().info("Expedition {} arrived at {} {} {} ({} resource blocks)", r.theme.id(), r.ox, r.oy,
                r.oz, r.nodes.size());
    }

    // ------------------------------------------------------------------ while it's here

    private void activeTick(Instant now) {
        Run r = run;
        List<Player> here = r.world.getPlayers();
        r.needed = Math.max(r.needed, Shares.hitsNeeded(cfg().hitsPerPlayer(), cfg().minHits(), here.size()));
        long left = Math.max(0, Duration.between(now, r.endsAt).getSeconds());
        int struck = r.struck();
        if (r.marker != null) {
            r.marker.show(heartLabel(r));
            r.marker.sparkle();
        }
        Component heart = r.heartBroken ? plugin.lang().get("expedition.bar-heart-broken")
                : plugin.lang().get("expedition.bar-heart", Placeholder.unparsed("hits", Integer.toString(struck)),
                Placeholder.unparsed("needed", Integer.toString(r.needed)));
        r.bar.name(plugin.lang().get("expedition.bar", Placeholder.component("theme", themeName(r.theme)),
                Placeholder.unparsed("time", String.format("%d:%02d", left / 60, left % 60)),
                Placeholder.unparsed("nodes", Integer.toString(r.nodes.size())),
                Placeholder.component("heart", heart)));
        r.bar.progress((float) Math.max(0, Math.min(1, left / (cfg().durationMinutes() * 60.0))));
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getWorld().equals(r.world)) {
                p.showBossBar(r.bar);
            } else {
                p.hideBossBar(r.bar);
            }
        }
        if (!r.warnedLeaving && left <= 60) {
            r.warnedLeaving = true;
            for (Player p : here) {
                plugin.lang().send(p, "expedition.leaving-soon");
            }
        }
        if (left == 0 || r.nodes.isEmpty() && r.heartBroken) {
            depart();
        }
    }

    /** {@code /expedition go}: the landing pad. */
    public void go(Player player) {
        if (!active()) {
            plugin.lang().send(player, "expedition.not-here", status());
            return;
        }
        player.teleportAsync(run.pad()).thenAccept(ok -> {
            if (ok) {
                plugin.lang().send(player, "expedition.welcome", Placeholder.component("theme", themeName(run.theme)));
            }
        });
    }

    // Before protection plugins and logging (LOW): the world is ours.
    @EventHandler(priority = EventPriority.LOW)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!inWorld(block.getWorld()) || bypass(event.getPlayer())) {
            return;
        }
        Run r = run;
        if (r == null || phase != Phase.ACTIVE) {
            event.setCancelled(true);
            return;
        }
        int rx = block.getX() - r.ox;
        int ry = block.getY() - r.oy;
        int rz = block.getZ() - r.oz;
        Player player = event.getPlayer();
        if (r.layout.heart(rx, ry, rz) && !r.heartBroken) {
            event.setCancelled(true);
            strike(r, player, block);
            return;
        }
        long key = Run.rel(rx, ry, rz);
        Blueprint.Block bp = r.layout.blueprint().get(rx, ry, rz);
        if (bp == null || !r.nodes.contains(key) || block.getType() != bp.material()) {
            event.setCancelled(true);
            return;
        }
        SkyRealmsSettings.Node node = cfg().themeConfig().get(r.theme).nodes().get(bp.material());
        if (node == null) {
            event.setCancelled(true);
            return;
        }
        r.nodes.remove(key);
        event.setDropItems(false);
        event.setExpToDrop(1 + ThreadLocalRandom.current().nextInt(3));
        List<ItemStack> loot = new ArrayList<>();
        for (ItemAmount a : node.loot()) {
            int n = a.roll(ThreadLocalRandom.current());
            if (n > 0) {
                loot.add(ItemStack.of(a.material(), n));
            }
        }
        Give.items(player, loot);
        int total = r.points.merge(player.getUniqueId(), node.points(), Integer::sum);
        player.sendActionBar(plugin.lang().get("expedition.points", Placeholder.unparsed("gained",
                Integer.toString(node.points())), Placeholder.unparsed("total", Integer.toString(total))));
    }

    private void strike(Run r, Player player, Block heart) {
        int mine = r.hits.merge(player.getUniqueId(), 1, Integer::sum);
        int struck = r.struck();
        heart.getWorld().spawnParticle(Particle.CRIT, heart.getLocation().add(0.5, 0.8, 0.5), 12, 0.3, 0.3, 0.3, 0.1);
        heart.getWorld().playSound(heart.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_HIT, 1f, 0.6f + 0.8f * struck / r.needed);
        player.sendActionBar(plugin.lang().get("expedition.strike", Placeholder.unparsed("mine", Integer.toString(mine)),
                Placeholder.unparsed("hits", Integer.toString(struck)), Placeholder.unparsed("needed",
                        Integer.toString(r.needed))));
        if (struck < r.needed) {
            if (r.marker != null) {
                r.marker.show(heartLabel(r));
            }
            return;
        }
        r.heartBroken = true;
        r.unmark();
        heart.setType(Material.AIR, false);
        heart.getWorld().spawnParticle(Particle.EXPLOSION, heart.getLocation().add(0.5, 0.5, 0.5), 3);
        heart.getWorld().playSound(heart.getLocation(), Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 1.5f, 0.7f);
        List<ItemAmount> loot = cfg().themeConfig().get(r.theme).heart();
        for (Map.Entry<UUID, Integer> e : r.hits.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) {
                continue;
            }
            List<ItemStack> mineLoot = new ArrayList<>();
            for (ItemAmount a : loot) {
                int n = Shares.heart(a.max(), e.getValue(), r.hits);
                if (n > 0) {
                    mineLoot.add(ItemStack.of(a.material(), n));
                }
            }
            Give.items(p, mineLoot);
            plugin.lang().send(p, "expedition.heart-share", Placeholder.unparsed("hits", Integer.toString(e.getValue())),
                    Placeholder.unparsed("total", Integer.toString(struck)));
        }
        for (Player p : r.world.getPlayers()) {
            plugin.lang().send(p, "expedition.heart-broken", Placeholder.unparsed("players",
                    Integer.toString(r.hits.size())));
        }
    }

    // ------------------------------------------------------------------ leaving

    private void depart() {
        Run r = run;
        phase = Phase.LEAVING;
        payOut(r);
        sendAway(r.world);
        hideBar(r);
        r.unmark();
        List<Blueprint.Block> blocks = new ArrayList<>(r.layout.blueprint().blocks());
        blocks.sort(Comparator.comparingInt(Blueprint.Block::y).reversed());
        clear(r, blocks, 0, () -> {
            deleteJournal();
            run = null;
            schedule(Instant.now());
        });
    }

    private void clear(Run r, List<Blueprint.Block> blocks, int from, Runnable done) {
        int to = Math.min(blocks.size(), from + BLOCKS_PER_TICK);
        for (int i = from; i < to; i++) {
            Blueprint.Block b = blocks.get(i);
            r.world.getBlockAt(r.ox + b.x(), r.oy + b.y(), r.oz + b.z()).setType(Material.AIR, false);
        }
        if (to < blocks.size()) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> clear(r, blocks, to, done), 1L);
        } else {
            done.run();
        }
    }

    private void payOut(Run r) {
        ModeEconomy economy = plugin.core().economy();
        Set<UUID> contributors = new HashSet<>(r.points.keySet());
        contributors.addAll(r.hits.keySet());
        Set<UUID> islandsCredited = new HashSet<>();
        long totalCoins = 0;
        for (UUID id : contributors) {
            int points = r.points.getOrDefault(id, 0);
            Player online = Bukkit.getPlayer(id);
            double tailwind = online == null ? 1.0 : plugin.core().tailwind().multiplier(online);
            long coins = Shares.coins(points, cfg().coinsPerPoint(), tailwind);
            totalCoins += coins;
            String txn = r.txnBase() + ":" + id;
            if (coins > 0 && economy != null) {
                plugin.async(() -> economy.deposit(id, coins, "expedition", txn), change -> { });
            }
            boolean counts = points >= cfg().minPoints() || r.hits.getOrDefault(id, 0) > 0;
            if (counts && online != null && cfg().bolts() > 0) {
                plugin.core().earning().award(online, cfg().bolts(), "expedition", cfg().boltsDailyCap(), txn + ":bolts");
            }
            if (counts) {
                plugin.islands().of(id).filter(i -> islandsCredited.add(i.id()))
                        .ifPresent(i -> plugin.tiers().record(i.id(), TierGoal.Type.EXPEDITION, null, 1));
            }
            if (online != null) {
                plugin.lang().send(online, "expedition.summary", Placeholder.unparsed("points", Integer.toString(points)),
                        Placeholder.unparsed("coins", String.format("%,d", coins)),
                        Placeholder.unparsed("hits", Integer.toString(r.hits.getOrDefault(id, 0))));
            }
        }
        plugin.announcer().local(plugin.lang().get("expedition.departed",
                Placeholder.component("theme", themeName(r.theme)),
                Placeholder.unparsed("players", Integer.toString(contributors.size()))));
        long coinsPaid = totalCoins;
        if (r.dbId > 0) {
            long id = r.dbId;
            boolean heart = r.heartBroken;
            int count = contributors.size();
            plugin.async(() -> {
                plugin.repo().endExpedition(id, Instant.now(), count, coinsPaid, heart);
                return null;
            }, v -> { });
        }
    }

    /** Everyone in the Expedition world goes back to their island, or the spawn. */
    private void sendAway(World world) {
        for (Player p : world.getPlayers()) {
            p.teleportAsync(homeOf(p));
        }
    }

    private Location homeOf(Player p) {
        return plugin.islands().of(p).flatMap(i -> plugin.islands().home(i.id())).orElse(plugin.core().worlds().spawn());
    }

    private void hideBar(Run r) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.hideBossBar(r.bar);
        }
    }

    // ------------------------------------------------------------------ the rest of the world's rules

    @EventHandler(priority = EventPriority.LOW)
    public void onPlace(BlockPlaceEvent event) {
        if (inWorld(event.getBlock().getWorld()) && !bypass(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null
                && inWorld(event.getClickedBlock().getWorld()) && !bypass(event.getPlayer())
                && event.getClickedBlock().getState() instanceof Container) {
            event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onBucket(PlayerBucketEmptyEvent event) {
        if (inWorld(event.getBlock().getWorld()) && !bypass(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onBucket(PlayerBucketFillEvent event) {
        if (inWorld(event.getBlock().getWorld()) && !bypass(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onExplode(EntityExplodeEvent event) {
        if (inWorld(event.getEntity().getWorld())) {
            event.blockList().clear();
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onExplode(BlockExplodeEvent event) {
        if (inWorld(event.getBlock().getWorld())) {
            event.blockList().clear();
        }
    }

    // No fighting over the loot.
    @EventHandler(priority = EventPriority.LOW)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player) || !inWorld(event.getEntity().getWorld())) {
            return;
        }
        boolean byPlayer = event.getDamager() instanceof Player
                || event.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player;
        if (byPlayer) {
            event.setCancelled(true);
        }
    }

    // A fall off the island lands back on the pad (or at home once it's gone).
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getFrom().getBlockY() == event.getTo().getBlockY() || !inWorld(event.getTo().getWorld())) {
            return;
        }
        Player p = event.getPlayer();
        int floor = (run == null ? cfg().height() : run.oy) - FALL_DEPTH;
        if (event.getTo().getY() >= floor) {
            return;
        }
        p.setFallDistance(0);
        if (active()) {
            p.teleportAsync(run.pad());
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 60, 0, false, false));
            plugin.lang().send(p, "expedition.fell");
        } else {
            p.teleportAsync(homeOf(p));
        }
    }

    // Logged out on an Expedition that has since left: back home.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        if (inWorld(p.getWorld()) && !active()) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (p.isOnline() && inWorld(p.getWorld())) {
                    p.teleportAsync(homeOf(p));
                }
            }, 20L);
        }
    }

    private static boolean bypass(Player p) {
        return p.getGameMode() == GameMode.CREATIVE && p.hasPermission("rapid.skyrealms.expedition.admin");
    }

    // ------------------------------------------------------------------ journal

    private void writeJournal(Run r) {
        try {
            Files.createDirectories(journal.getParent());
            Files.writeString(journal, r.theme.id() + " " + r.seed + " " + r.ox + " " + r.oy + " " + r.oz,
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            plugin.getSLF4JLogger().warn("Couldn't write {}: {}", journal, e.getMessage());
        }
    }

    private void deleteJournal() {
        try {
            Files.deleteIfExists(journal);
        } catch (IOException e) {
            plugin.getSLF4JLogger().warn("Couldn't remove {}: {}", journal, e.getMessage());
        }
    }

    /** Removes an Expedition a crash left behind, by building its blueprint again as air. */
    private void recoverJournal() {
        if (!Files.exists(journal)) {
            return;
        }
        try {
            String[] p = Files.readString(journal, StandardCharsets.UTF_8).trim().split(" ");
            Optional<Theme> theme = Theme.byId(p[0]);
            World world = world();
            if (theme.isEmpty() || world == null) {
                plugin.getSLF4JLogger().warn("{} names an Expedition that can't be removed now ({}); left in place",
                        journal, String.join(" ", p));
                return;
            }
            Run leftover = new Run(theme.get(), Long.parseLong(p[1]), world, Integer.parseInt(p[2]),
                    Integer.parseInt(p[3]), Integer.parseInt(p[4]), BossBar.bossBar(Component.empty(), 0f,
                    BossBar.Color.PURPLE, BossBar.Overlay.PROGRESS));
            List<Blueprint.Block> blocks = new ArrayList<>(leftover.layout.blueprint().blocks());
            loadChunks(leftover).thenRun(() -> Bukkit.getScheduler().runTask(plugin, () -> {
                sendAway(world);
                clear(leftover, blocks, 0, this::deleteJournal);
                plugin.getSLF4JLogger().info("Removed the Expedition a crash left at {} {} {}", leftover.ox,
                        leftover.oy, leftover.oz);
            }));
        } catch (IOException | RuntimeException e) {
            plugin.getSLF4JLogger().warn("Couldn't read {}: {}", journal, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ text

    public Component themeName(Theme theme) {
        return plugin.lang().get("expedition.theme." + theme.id() + ".name");
    }

    /** The label over the heart: its name and the strikes so far. */
    private Component heartLabel(Run r) {
        return plugin.lang().get("expedition.heart-label",
                Placeholder.component("heart", plugin.lang().get("expedition.theme." + r.theme.id() + ".heart")),
                Placeholder.unparsed("hits", Integer.toString(r.struck())),
                Placeholder.unparsed("needed", Integer.toString(r.needed)));
    }

    /** The status line ({@code /expedition}, the "not here" reply). */
    public net.kyori.adventure.text.minimessage.tag.resolver.TagResolver status() {
        Duration left = remaining();
        long minutes = Math.max(1, (left.getSeconds() + 59) / 60);
        return net.kyori.adventure.text.minimessage.tag.resolver.TagResolver.resolver(
                Placeholder.component("theme", themeName(theme())),
                Placeholder.unparsed("minutes", Long.toString(minutes)));
    }
}
