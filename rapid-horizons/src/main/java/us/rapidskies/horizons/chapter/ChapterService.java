package us.rapidskies.horizons.chapter;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import us.rapidskies.horizons.HorizonsSettings.Chapter;
import us.rapidskies.horizons.HorizonsSettings.ChapterEntry;
import us.rapidskies.horizons.RapidHorizons;
import us.rapidskies.horizons.data.ChapterRepository;
import us.rapidskies.horizons.data.ChapterRepository.EntryRow;
import us.rapidskies.horizons.frontier.FrontierRow;
import us.rapidskies.horizons.frontier.FrontierService;
import us.rapidskies.horizons.frontier.FrontierService.Place;

/**
 * Chapters (PLAN.md §5.2): each Frontier carries a lore Chapter with a collection log of
 * rare finds, event participation and exploration milestones ({@link ChapterGoal}). Progress
 * only counts in that Frontier (its overworld, Nether and End) while it's open. Finishing
 * every entry records the Chapter's cosmetic in {@code chapter_completions} (the cosmetics
 * engine grants it, phase 5) and pays its Bolts once. Missing a Chapter never affects
 * gameplay.
 *
 * <p>Online players' progress for the current Chapter is held in memory, loaded before they
 * join and written through on every change (changes are rare: a diamond, a new biome).
 */
public final class ChapterService implements Listener {

    private static final long MOVE_CHECK_MILLIS = 1000;

    /** One entry's state for an online player. */
    static final class EntryState {
        int progress;
        final Set<String> seen = new LinkedHashSet<>();
        Instant doneAt;
    }

    /** An online player's log for one Chapter. */
    static final class Log {
        final String chapter;
        final Map<String, EntryState> entries = new HashMap<>();
        volatile boolean completed;

        Log(String chapter) {
            this.chapter = chapter;
        }

        EntryState of(String entry) {
            return entries.computeIfAbsent(entry, e -> new EntryState());
        }
    }

    private final RapidHorizons plugin;
    private final ChapterRepository repository;
    private final FrontierService frontier;
    private final Map<UUID, Log> logs = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastMoveCheck = new ConcurrentHashMap<>();

    public ChapterService(RapidHorizons plugin, ChapterRepository repository, FrontierService frontier) {
        this.plugin = plugin;
        this.repository = repository;
        this.frontier = frontier;
    }

    public void start() {
        plugin.trades().onNaturalBreak(this::naturalBreak);
        plugin.trades().watchPlacements(this::watchesMine);
        // At startup (players may join before the Frontier is loaded) and on every rotation.
        frontier.onChange(row -> reloadAll());
    }

    /** The open Frontier's Chapter, if it has one configured. */
    public Optional<Chapter> current() {
        return frontier.chapter().flatMap(id -> plugin.settings().chapter(id));
    }

    /** {@code player}'s log for the current Chapter, or null if it isn't loaded. */
    Log log(UUID player) {
        Log log = logs.get(player);
        Optional<Chapter> chapter = current();
        return log != null && chapter.isPresent() && log.chapter.equals(chapter.get().id()) ? log : null;
    }

    /** Progress of {@code entry} for an online player (0 if nothing yet). */
    public int progress(UUID player, String entry) {
        Log log = log(player);
        EntryState s = log == null ? null : log.entries.get(entry);
        return s == null ? 0 : s.progress;
    }

    public boolean done(UUID player, String entry) {
        Log log = log(player);
        EntryState s = log == null ? null : log.entries.get(entry);
        return s != null && s.doneAt != null;
    }

    public boolean completed(UUID player) {
        Log log = log(player);
        return log != null && log.completed;
    }

    public boolean loaded(UUID player) {
        return log(player) != null;
    }

    // ------------------------------------------------------------------ loading

    private Log load(UUID player, Chapter chapter) throws SQLException {
        Log log = new Log(chapter.id());
        for (Map.Entry<String, EntryRow> e : repository.progress(player, chapter.id()).entrySet()) {
            EntryState s = log.of(e.getKey());
            s.progress = e.getValue().progress();
            s.seen.addAll(e.getValue().seen());
            s.doneAt = e.getValue().completedAt().orElse(null);
        }
        log.completed = repository.completed(player, chapter.id());
        return log;
    }

    // MONITOR: only logins that go ahead. Async thread.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        Optional<Chapter> chapter = current();
        if (chapter.isEmpty()) {
            return;
        }
        try {
            logs.put(event.getUniqueId(), load(event.getUniqueId(), chapter.get()));
        } catch (SQLException e) {
            plugin.getSLF4JLogger().warn("Couldn't load the Chapter log of {}: {}", event.getName(), e.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        logs.remove(event.getPlayer().getUniqueId());
        lastMoveCheck.remove(event.getPlayer().getUniqueId());
    }

    /** The open Frontier (and so the Chapter) was set: load everyone's log for it. */
    private void reloadAll() {
        logs.clear();
        Optional<Chapter> chapter = current();
        if (chapter.isEmpty()) {
            return;
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            plugin.core().database().async(() -> load(id, chapter.get())).thenAccept(log -> {
                if (Bukkit.getPlayer(id) != null) {
                    logs.put(id, log);
                }
            }).exceptionally(e -> {
                plugin.getSLF4JLogger().warn("Couldn't load the Chapter log of {}: {}", p.getName(), e.getMessage());
                return null;
            });
        }
    }

    // ------------------------------------------------------------------ progress

    /** Adds to every current-Chapter entry {@code matches} accepts. Main thread. */
    private void advance(Player player, Predicate<ChapterGoal> matches, int amount, String seen) {
        Optional<Chapter> chapter = current();
        Log log = log(player.getUniqueId());
        if (chapter.isEmpty() || log == null) {
            return;
        }
        for (ChapterEntry entry : chapter.get().entries()) {
            if (matches.test(entry.goal())) {
                advance(player, chapter.get(), log, entry, amount, seen);
            }
        }
    }

    private void advance(Player player, Chapter chapter, Log log, ChapterEntry entry, int amount, String seen) {
        EntryState s = log.of(entry.id());
        if (s.doneAt != null) {
            return;
        }
        if (seen != null) {
            if (!s.seen.add(seen)) {
                return;
            }
            s.progress = s.seen.size();
        } else {
            s.progress = Math.min(entry.amount(), s.progress + amount);
        }
        var name = plugin.lang().get("chapter." + chapter.id() + ".entries." + entry.id() + ".name");
        if (s.progress >= entry.amount()) {
            s.doneAt = Instant.now();
            plugin.lang().send(player, "chapter.entry-done", Placeholder.component("entry", name),
                    Placeholder.component("chapter", plugin.lang().get("chapter." + chapter.id() + ".name")));
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.4f);
        } else if (entry.amount() > 1) {
            player.sendActionBar(plugin.lang().get("chapter.entry-progress", Placeholder.component("entry", name),
                    Placeholder.unparsed("progress", Integer.toString(s.progress)),
                    Placeholder.unparsed("amount", Integer.toString(entry.amount()))));
        }
        save(player, chapter, entry.id(), s);
        if (s.doneAt != null) {
            checkComplete(player, chapter, log);
        }
    }

    private void save(Player player, Chapter chapter, String entry, EntryState s) {
        EntryRow row = new EntryRow(s.progress, Set.copyOf(s.seen), Optional.ofNullable(s.doneAt));
        UUID id = player.getUniqueId();
        plugin.core().database().async(() -> {
            repository.save(id, chapter.id(), entry, row);
            return null;
        }).exceptionally(e -> {
            plugin.getSLF4JLogger().warn("Couldn't save Chapter progress of {}: {}", player.getName(), e.getMessage());
            return null;
        });
    }

    private void checkComplete(Player player, Chapter chapter, Log log) {
        if (log.completed) {
            return;
        }
        for (ChapterEntry e : chapter.entries()) {
            EntryState s = log.entries.get(e.id());
            if (s == null || s.doneAt == null) {
                return;
            }
        }
        log.completed = true;
        UUID id = player.getUniqueId();
        plugin.core().database().async(() -> repository.complete(id, chapter.id(), chapter.cosmetic(), Instant.now()))
                .thenAccept(first -> {
                    if (first) {
                        Bukkit.getScheduler().runTask(plugin, () -> reward(player, chapter));
                    }
                }).exceptionally(e -> {
                    log.completed = false;
                    plugin.getSLF4JLogger().error("Couldn't record that {} finished {}: {}", player.getName(),
                            chapter.id(), e.getMessage());
                    return null;
                });
    }

    private void reward(Player player, Chapter chapter) {
        var name = plugin.lang().get("chapter." + chapter.id() + ".name");
        player.showTitle(Title.title(plugin.lang().get("chapter.completed-title"), name,
                Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(4), Duration.ofSeconds(1))));
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        plugin.lang().send(player, "chapter.completed", Placeholder.component("chapter", name),
                Placeholder.component("reward", plugin.lang().get("chapter." + chapter.id() + ".reward")));
        Bukkit.getServer().sendMessage(plugin.lang().get("chapter.completed-broadcast",
                Placeholder.unparsed("player", player.getName()), Placeholder.component("chapter", name)));
        if (chapter.bolts() > 0) {
            plugin.core().earning().award(player, chapter.bolts(), "chapter", 0,
                    "chapter:" + chapter.id() + ":" + player.getUniqueId()).thenAccept(paid -> {
                        if (paid > 0) {
                            Bukkit.getScheduler().runTask(plugin, () -> plugin.lang().send(player, "rewards.bolts",
                                    Placeholder.unparsed("amount", Long.toString(paid))));
                        }
                    });
        }
        plugin.getSLF4JLogger().info("{} finished {} (cosmetic '{}')", player.getName(), chapter.id(),
                chapter.cosmetic());
    }

    // ------------------------------------------------------------------ what counts

    /** Skyborne or the dragon fight won with {@code player} rewarded. */
    public void eventWon(Player player, ChapterGoal.Kind kind) {
        advance(player, g -> g.kind() == kind, 1, null);
    }

    private boolean watchesMine(Material material) {
        String key = material.getKey().getKey();
        return current().map(c -> c.entries().stream().anyMatch(e -> e.goal().minesBlock(key))).orElse(false);
    }

    private void naturalBreak(Player player, Block block) {
        if (!frontier.inFrontier(block.getWorld())) {
            return;
        }
        String key = block.getType().getKey().getKey();
        advance(player, g -> g.minesBlock(key), 1, null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKill(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        Player killer = dead.getKiller();
        if (killer == null || !frontier.inFrontier(dead.getWorld()) || dead.fromMobSpawner()) {
            return;
        }
        CreatureSpawnEvent.SpawnReason reason = dead.getEntitySpawnReason();
        if (reason == CreatureSpawnEvent.SpawnReason.SPAWNER_EGG || reason == CreatureSpawnEvent.SpawnReason.COMMAND
                || reason == CreatureSpawnEvent.SpawnReason.CUSTOM
                || reason == CreatureSpawnEvent.SpawnReason.DISPENSE_EGG) {
            return;
        }
        String key = dead.getType().getKey().getKey();
        advance(killer, g -> g.kind() == ChapterGoal.Kind.KILL && g.target().equals(key), 1, null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH || !(event.getCaught() instanceof Item item)
                || !frontier.inFrontier(event.getPlayer().getWorld())) {
            return;
        }
        String key = item.getItemStack().getType().getKey().getKey();
        advance(event.getPlayer(), g -> g.kind() == ChapterGoal.Kind.FISH && g.target().equals(key),
                item.getItemStack().getAmount(), null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        explore(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedBlock()) {
            return;
        }
        Player player = event.getPlayer();
        long now = System.currentTimeMillis();
        Long last = lastMoveCheck.get(player.getUniqueId());
        if (last != null && now - last < MOVE_CHECK_MILLIS) {
            return;
        }
        lastMoveCheck.put(player.getUniqueId(), now);
        explore(player);
    }

    /** Visits, distance and biomes, where {@code player} stands. */
    private void explore(Player player) {
        if (log(player.getUniqueId()) == null) {
            return;
        }
        Place place = frontier.place(player.getWorld());
        if (place != Place.FRONTIER && place != Place.NETHER && place != Place.END) {
            return;
        }
        Location at = player.getLocation();
        String visited = switch (place) {
            case NETHER -> "nether";
            case END -> "end";
            default -> "frontier";
        };
        advance(player, g -> g.kind() == ChapterGoal.Kind.VISIT && g.target().equals(visited), 1, null);
        if (place == Place.FRONTIER) {
            if (frontier.inWildlands(at)) {
                advance(player, g -> g.kind() == ChapterGoal.Kind.VISIT && g.target().equals("wildlands"), 1, null);
            }
            int distance = frontier.wildlands().distance(at.getX(), at.getZ());
            advance(player, g -> g.kind() == ChapterGoal.Kind.DISTANCE && distance >= g.blocks(), 1, null);
        }
        String biome = at.getBlock().getBiome().key().asString();
        advance(player, g -> g.kind() == ChapterGoal.Kind.BIOMES, 1, biome);
    }

    /** The current Frontier for the menu. */
    public Optional<FrontierRow> frontier() {
        return frontier.current();
    }
}
