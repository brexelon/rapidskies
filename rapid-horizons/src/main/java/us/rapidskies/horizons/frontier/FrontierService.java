package us.rapidskies.horizons.frontier;

import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import us.rapidskies.common.time.Durations;
import us.rapidskies.horizons.HorizonsSettings;
import us.rapidskies.horizons.RapidHorizons;
import us.rapidskies.horizons.data.FrontierRepository;
import us.rapidskies.horizons.event.Announcer;
import us.rapidskies.horizons.frontier.FrontierSchedule.Phase;

/**
 * The Frontier rotation (PLAN.md §5.2, phase 3b): one open Frontier at a time, each with a
 * modifier, its own Nether and End, the Wildlands ring and a Chapter.
 *
 * <ul>
 *   <li><b>Lifecycle.</b> A Frontier is open {@code length-days}. For the last
 *       {@code warning-days} players are warned (network-wide once, then on joining and on
 *       entering it), and the next Frontier is created and pre-generated with Chunky. At
 *       closing time everyone inside is sent to the spawn, the old worlds are unloaded and
 *       archived, and the new one opens with a network announcement. With no Frontier at
 *       all (the first start), one opens at once.</li>
 *   <li><b>State</b> is {@code frontier_history}; the database changes first and the server
 *       follows, so a failed write never leaves two Frontiers open.</li>
 *   <li><b>Players</b> who log out in a Frontier that has closed by the time they're back log
 *       in at the spawn and are told why.</li>
 * </ul>
 *
 * <p>Portals into the rotating Nether and End are {@link DimensionRouter}'s, the End's
 * dragon fight {@link DragonFight}'s, the modifiers' rules {@link ModifierEffects}' and the
 * Wildlands' notices and bonuses {@link WildlandsListener}'s. One Horizons instance runs the
 * rotation ({@code frontier.enabled}); a second one would only follow it after a restart.
 */
public final class FrontierService implements Listener {

    public static final String ADMIN = "rapid.horizons.frontier.admin";
    /** Where a {@code /frontier go} landing stays clear of the Wildlands' edge. */
    private static final int SAFE_MARGIN = 64;

    /** What a world is, as far as the rotation is concerned. */
    public enum Place {
        HEARTLAND, FRONTIER, NETHER, END, OTHER
    }

    private final RapidHorizons plugin;
    private final FrontierRepository repository;
    private final FrontierWorlds worlds;
    private final Announcer announcer;
    private final Random random = new Random();
    private final List<Consumer<FrontierRow>> rotationListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<FrontierRow>> changeListeners = new CopyOnWriteArrayList<>();
    /** Frontier each online player last logged out in, as loaded at pre-login (absent: none). */
    private final Map<UUID, Integer> loggedOutIn = new ConcurrentHashMap<>();
    /** Players put at the spawn because their Frontier closed while they were away. */
    private final Set<UUID> movedHome = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> travelCooldown = new ConcurrentHashMap<>();
    private volatile FrontierRow current;
    private volatile FrontierRow preparing;
    /** Set once the live Frontiers have been read and loaded at startup (or the rotation is off). */
    private volatile boolean ready;
    private volatile WildlandsZone wildlands = new WildlandsZone(0, 0, 0);
    private ChunkyPregen chunky;
    private BukkitTask ticker;
    private boolean busy;
    private int warnedFor = -1;
    private int endSoonFor = -1;
    private int endOpenFor = -1;

    public FrontierService(RapidHorizons plugin, FrontierRepository repository, Announcer announcer) {
        this.plugin = plugin;
        this.repository = repository;
        this.announcer = announcer;
        this.worlds = new FrontierWorlds(plugin.getSLF4JLogger(), plugin.worldHost());
    }

    private HorizonsSettings.Frontier settings() {
        return plugin.settings().frontier();
    }

    /**
     * Main thread, after every plugin is enabled (Chunky, Multiverse). Puts the Heartland under
     * the world host (Multiverse imports it as it is), loads the live Frontiers and starts the clock.
     */
    public void start() {
        if (plugin.getServer().getPluginManager().isPluginEnabled("Chunky")) {
            chunky = ChunkyPregen.create();
        }
        World home = heartland();
        if (home != null) {
            try {
                plugin.worldHost().adopt(home, "Heartland");
            } catch (RuntimeException e) {
                plugin.getSLF4JLogger().warn("Couldn't hand the Heartland to {}: {}", plugin.worldHost().describe(),
                        e.getMessage());
            }
        }
        if (!settings().enabled()) {
            plugin.getSLF4JLogger().info("The Frontier rotation is off on this server (frontier.enabled)");
            ready = true;
            return;
        }
        busy = true;
        async(repository::live).thenAccept(rows -> sync(() -> {
            try {
                for (FrontierRow row : rows) {
                    load(row);
                    if (row.state() == FrontierRow.State.OPEN) {
                        becomeCurrent(row);
                    } else if (row.state() == FrontierRow.State.PREPARING) {
                        preparing = row;
                        pregenerate(row);
                    }
                }
            } catch (RuntimeException e) {
                plugin.getSLF4JLogger().error("Couldn't load the Frontier worlds", e);
            }
            busy = false;
            ready = true;
            fillMissingChapters();
            if (current == null) {
                plugin.getSLF4JLogger().info("No Frontier is open; opening the first one");
                rotate(msg -> plugin.getSLF4JLogger().info("{}", msg));
            }
            pruneArchives();
        })).exceptionally(e -> {
            busy = false;
            ready = true;
            plugin.getSLF4JLogger().error("Couldn't read frontier_history; the Frontier stays closed until a restart",
                    e);
            return null;
        });
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 200L, 400L);
    }

    public void stop() {
        if (ticker != null) {
            ticker.cancel();
        }
    }

    /** Called (main thread) once a new Frontier has opened, with it. */
    public void onRotate(Consumer<FrontierRow> listener) {
        rotationListeners.add(listener);
    }

    /**
     * Called (main thread) whenever the open Frontier is set: once it's loaded at startup, and
     * after every rotation.
     */
    public void onChange(Consumer<FrontierRow> listener) {
        changeListeners.add(listener);
    }

    /** Whether startup has finished reading and loading the Frontiers. */
    public boolean ready() {
        return ready;
    }

    // ------------------------------------------------------------------ what's where

    public Optional<FrontierRow> current() {
        return Optional.ofNullable(current);
    }

    public Optional<FrontierRow> preparing() {
        return Optional.ofNullable(preparing);
    }

    public World overworld() {
        FrontierRow c = current;
        return c == null ? null : FrontierWorlds.loaded(c.worldKey());
    }

    /** The current rotating Nether, or null (no Frontier, or the Nether doesn't rotate). */
    public World nether() {
        FrontierRow c = current;
        return c == null || !settings().nether() ? null : FrontierWorlds.loaded(c.netherKey());
    }

    public World end() {
        FrontierRow c = current;
        return c == null || !settings().end().enabled() ? null : FrontierWorlds.loaded(c.endKey());
    }

    public World heartland() {
        return FrontierWorlds.loaded(plugin.settings().rtp().world());
    }

    public Place place(World world) {
        if (world == null) {
            return Place.OTHER;
        }
        String key = world.getKey().asString();
        if (key.equals(plugin.settings().rtp().world())) {
            return Place.HEARTLAND;
        }
        FrontierRow c = current;
        if (c != null) {
            if (key.equals(c.worldKey())) {
                return Place.FRONTIER;
            }
            if (key.equals(c.netherKey())) {
                return Place.NETHER;
            }
            if (key.equals(c.endKey())) {
                return Place.END;
            }
        }
        return Place.OTHER;
    }

    /** Whether {@code world} is one of the open Frontier's worlds (overworld, Nether or End). */
    public boolean inFrontier(World world) {
        Place p = place(world);
        return p == Place.FRONTIER || p == Place.NETHER || p == Place.END;
    }

    public WildlandsZone wildlands() {
        return wildlands;
    }

    /** Whether {@code at} is in the open Frontier's Wildlands (forced PvP, richer resources). */
    public boolean inWildlands(Location at) {
        FrontierRow c = current;
        return c != null && at.getWorld() != null && at.getWorld().getKey().asString().equals(c.worldKey())
                && wildlands.contains(at.getX(), at.getZ());
    }

    /** Trade income multiplier where {@code player} stands. */
    public double tradeMultiplier(Player player) {
        return inWildlands(player.getLocation()) ? 1 + settings().wildlands().tradeBonusPercent() / 100.0 : 1.0;
    }

    public Optional<String> chapter() {
        FrontierRow c = current;
        return c == null ? Optional.empty() : c.chapter();
    }

    public boolean endOpen() {
        FrontierRow c = current;
        return c != null && settings().end().enabled()
                && FrontierSchedule.endOpen(Instant.now(), c.endOpensAt().orElse(null));
    }

    /** The name players see for a Frontier ("Frontier · Drought"). */
    public Component title(FrontierRow row) {
        return plugin.lang().get("frontier.title", Placeholder.unparsed("number", Integer.toString(row.id())),
                Placeholder.component("modifier", modifierName(row.modifier())));
    }

    public Component modifierName(Modifier modifier) {
        return plugin.lang().get("frontier.modifier." + modifier.id() + ".name");
    }

    // ------------------------------------------------------------------ the clock

    private void tick() {
        FrontierRow c = current;
        if (c == null || busy || !settings().enabled()) {
            return;
        }
        Instant now = Instant.now();
        Phase phase = FrontierSchedule.phase(now, c.closesAt().orElse(now), Duration.ofDays(settings().warningDays()));
        if (phase == Phase.DUE) {
            rotate(msg -> plugin.getSLF4JLogger().info("{}", msg));
            return;
        }
        if (phase == Phase.WARNING) {
            if (warnedFor != c.id()) {
                warnedFor = c.id();
                announcer.network(plugin.lang().get("frontier.reset-warning", resetLeft(c)));
            }
            if (preparing == null && settings().pregenerate()) {
                prepare(Optional.empty(), msg -> plugin.getSLF4JLogger().info("{}", msg));
            }
        }
        endAnnouncements(c, now);
    }

    private void endAnnouncements(FrontierRow c, Instant now) {
        if (!settings().end().enabled() || c.endOpensAt().isEmpty() || c.dragonKilledAt().isPresent()) {
            return;
        }
        Instant opens = c.endOpensAt().get();
        Duration ahead = Duration.ofMinutes(settings().end().announceMinutes());
        if (endSoonFor != c.id() && now.isAfter(opens.minus(ahead)) && now.isBefore(opens)) {
            endSoonFor = c.id();
            announcer.network(plugin.lang().get("frontier.end.soon",
                    Placeholder.unparsed("time", Durations.format(FrontierSchedule.until(now, opens)))));
        }
        // Only announce an opening that just happened, not one from days ago after a restart.
        if (endOpenFor != c.id() && !now.isBefore(opens) && now.isBefore(opens.plus(Duration.ofHours(1)))) {
            endOpenFor = c.id();
            announcer.network(plugin.lang().get("frontier.end.open"));
        }
    }

    private TagResolver resetLeft(FrontierRow c) {
        return Placeholder.unparsed("time", Durations.format(
                FrontierSchedule.until(Instant.now(), c.closesAt().orElse(Instant.now()))));
    }

    // ------------------------------------------------------------------ preparing and rotating

    /** Creates the next Frontier (PREPARING) and starts pre-generating it. */
    public void prepare(Optional<Modifier> forced, Consumer<String> report) {
        if (busy) {
            report.accept("The Frontier is busy (creating or rotating); try again in a moment.");
            return;
        }
        if (preparing != null) {
            report.accept("Frontier #" + preparing.id() + " is already being prepared.");
            return;
        }
        busy = true;
        createRow(forced).thenAccept(row -> sync(() -> {
            try {
                load(row);
                preparing = row;
                pregenerate(row);
                report.accept("Prepared Frontier #" + row.id() + " (" + row.modifier().id() + ")"
                        + (chunky == null ? "; Chunky isn't installed, so it generates as players explore" : ""));
            } catch (RuntimeException e) {
                plugin.getSLF4JLogger().error("Couldn't create the worlds of Frontier #{}", row.id(), e);
                report.accept("Couldn't create its worlds; see the console.");
            } finally {
                busy = false;
            }
        })).exceptionally(e -> {
            sync(() -> busy = false);
            plugin.getSLF4JLogger().error("Couldn't create the next Frontier", e);
            report.accept("Couldn't create the next Frontier: " + e.getMessage());
            return null;
        });
    }

    private CompletableFuture<FrontierRow> createRow(Optional<Modifier> forced) {
        List<Modifier> cycle = settings().modifiers();
        List<String> chapters = settings().chapters();
        long seed = random.nextLong();
        return async(() -> {
            Optional<FrontierRow> latest = repository.latest();
            Modifier modifier = forced.orElseGet(() -> FrontierSchedule.next(cycle,
                    latest.map(FrontierRow::modifier)));
            int number = repository.count() + 1;
            Optional<String> chapter = FrontierSchedule.chapter(number, chapters);
            if (chapter.isEmpty()) {
                warnNoChapter(number, chapters);
            }
            return repository.create(modifier, seed, chapter, Instant.now());
        });
    }

    private void warnNoChapter(int number, List<String> chapters) {
        if (chapters.isEmpty()) {
            plugin.getSLF4JLogger().warn("Frontier #{} has no Chapter: frontier.chapters is empty. Add one to the "
                    + "config; it's given to this Frontier on the next /horizons reload.", number);
        } else {
            plugin.getSLF4JLogger().warn("Frontier #{} has no Chapter: frontier.chapters lists only {}. Add the next "
                    + "Chapter to the config; it's given to this Frontier on the next /horizons reload.", number,
                    chapters.size());
        }
    }

    /**
     * Gives the open and the preparing Frontier their Chapter if they have none and
     * {@code frontier.chapters} now lists one for their number. A Frontier created while the list
     * was empty or too short (a config from before Chapters, a missed live-ops update) gets it as
     * soon as the config does, at startup or on {@code /horizons reload}, instead of going a
     * month without one. Main thread.
     */
    public void fillMissingChapters() {
        // A Chapter id the config doesn't have (a hand-edited row, a renamed Chapter): /chapter
        // shows nothing. Not replaced, since progress is kept by Chapter id.
        for (FrontierRow row : java.util.stream.Stream.of(current, preparing).filter(r -> r != null).toList()) {
            row.chapter().filter(id -> plugin.settings().chapter(id).isEmpty()).ifPresent(id ->
                    plugin.getSLF4JLogger().warn("Frontier #{} has Chapter '{}', which isn't under `chapters` in the "
                            + "config ({}), so it has no Chapter. Add it there, or set frontier_history.chapter of "
                            + "id {} to one of those and restart.", row.id(), id,
                            plugin.settings().chapters().stream().map(HorizonsSettings.Chapter::id).toList(),
                            row.id()));
        }
        List<FrontierRow> missing = java.util.stream.Stream.of(current, preparing)
                .filter(r -> r != null && r.chapter().isEmpty()).toList();
        if (missing.isEmpty()) {
            return;
        }
        List<String> chapters = settings().chapters();
        async(() -> {
            Map<Integer, String> assigned = new java.util.HashMap<>();
            for (FrontierRow row : missing) {
                int number = repository.number(row.id());
                Optional<String> chapter = FrontierSchedule.chapter(number, chapters);
                if (chapter.isEmpty()) {
                    warnNoChapter(number, chapters);
                } else if (repository.setChapterIfMissing(row.id(), chapter.get())) {
                    assigned.put(row.id(), chapter.get());
                }
            }
            return assigned;
        }).thenAccept(assigned -> sync(() -> assigned.forEach((id, chapter) -> {
            plugin.getSLF4JLogger().info("Frontier #{} had no Chapter; it now has {}", id, chapter);
            FrontierRow p = preparing;
            if (p != null && p.id() == id && p.chapter().isEmpty()) {
                preparing = withChapter(p, chapter);
            }
            FrontierRow c = current;
            if (c != null && c.id() == id && c.chapter().isEmpty()) {
                current = withChapter(c, chapter);
                for (Consumer<FrontierRow> listener : changeListeners) {
                    try {
                        listener.accept(current);
                    } catch (RuntimeException e) {
                        plugin.getSLF4JLogger().error("A Frontier change listener failed", e);
                    }
                }
            }
        }))).exceptionally(e -> {
            plugin.getSLF4JLogger().error("Couldn't give the Frontier its Chapter", e);
            return null;
        });
    }

    private static FrontierRow withChapter(FrontierRow r, String chapter) {
        return new FrontierRow(r.id(), r.modifier(), r.seed(), Optional.of(chapter), r.state(), r.createdAt(),
                r.openedAt(), r.closesAt(), r.closedAt(), r.endOpensAt(), r.dragonKilledAt(), r.archivePath());
    }

    /**
     * Closes the open Frontier (if any) and opens the prepared one, creating it first if
     * there isn't one. Main thread.
     */
    public void rotate(Consumer<String> report) {
        if (busy) {
            report.accept("The Frontier is busy; try again in a moment.");
            return;
        }
        busy = true;
        CompletableFuture<FrontierRow> next = preparing != null ? CompletableFuture.completedFuture(preparing)
                : createRow(Optional.empty()).thenCompose(row -> {
                    CompletableFuture<FrontierRow> loaded = new CompletableFuture<>();
                    sync(() -> {
                        try {
                            load(row);
                            loaded.complete(row);
                        } catch (RuntimeException e) {
                            loaded.completeExceptionally(e);
                        }
                    });
                    return loaded;
                });
        FrontierRow old = current;
        Instant now = Instant.now();
        Instant closesAt = now.plus(Duration.ofDays(settings().lengthDays()));
        Instant endOpensAt = settings().end().enabled() ? now.plus(Duration.ofHours(settings().end().dragonDelayHours()))
                : null;
        next.thenCompose(row -> async(() -> {
            repository.rotate(old == null ? OptionalInt.empty() : OptionalInt.of(old.id()), row.id(), now, closesAt,
                    endOpensAt);
            return row;
        })).thenAccept(row -> sync(() -> {
            try {
                FrontierRow opened = new FrontierRow(row.id(), row.modifier(), row.seed(), row.chapter(),
                        FrontierRow.State.OPEN, row.createdAt(), Optional.of(now), Optional.of(closesAt),
                        Optional.empty(), Optional.ofNullable(endOpensAt), Optional.empty(), Optional.empty());
                preparing = null;
                if (old != null) {
                    close(old);
                }
                becomeCurrent(opened);
                report.accept("Frontier #" + opened.id() + " (" + opened.modifier().id() + ") is open");
                announcer.network(plugin.lang().get("frontier.opened", Placeholder.component("frontier", title(opened)),
                        Placeholder.component("about", plugin.lang().get("frontier.modifier."
                                + opened.modifier().id() + ".about"))));
                for (Consumer<FrontierRow> listener : rotationListeners) {
                    try {
                        listener.accept(opened);
                    } catch (RuntimeException e) {
                        plugin.getSLF4JLogger().error("A Frontier rotation listener failed", e);
                    }
                }
            } finally {
                busy = false;
            }
        })).exceptionally(e -> {
            sync(() -> busy = false);
            plugin.getSLF4JLogger().error("The Frontier rotation failed; it's tried again shortly", e);
            report.accept("The rotation failed: " + e.getMessage());
            return null;
        });
    }

    /** Sends everyone in {@code old}'s worlds to the spawn, unloads and archives them. */
    private void close(FrontierRow old) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (old.owns(p.getWorld().getKey().asString())) {
                plugin.core().worlds().teleport(p);
                plugin.lang().send(p, "frontier.closed-here");
            }
        }
        if (chunky != null) {
            for (String key : List.of(old.worldKey(), old.netherKey(), old.endKey())) {
                World w = FrontierWorlds.loaded(key);
                if (w != null) {
                    chunky.cancel(w);
                }
            }
        }
        // Teleports are async: unload a moment later, once everyone has left.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (old.owns(p.getWorld().getKey().asString())) {
                    p.teleport(plugin.core().worlds().spawn());
                }
            }
            List<Path> folders = worlds.unload(old);
            for (String key : List.of(old.worldKey(), old.netherKey(), old.endKey())) {
                plugin.core().worlds().forgetWorldName(key);
            }
            Path root = Bukkit.getWorldContainer().toPath().resolve(settings().archiveFolder());
            async(() -> {
                try {
                    Path archived = worlds.archive(old.id(), folders, root);
                    repository.setArchivePath(old.id(), archived.toString());
                    plugin.getSLF4JLogger().info("Archived Frontier #{} to {}", old.id(), archived);
                } catch (IOException e) {
                    plugin.getSLF4JLogger().error("Couldn't archive Frontier #{}: {}", old.id(), e.getMessage());
                }
                return null;
            });
        }, 40L);
    }

    private void becomeCurrent(FrontierRow row) {
        current = row;
        wildlands = new WildlandsZone(0, 0, settings().wildlands().innerRadius());
        name(row);
        for (Consumer<FrontierRow> listener : changeListeners) {
            try {
                listener.accept(row);
            } catch (RuntimeException e) {
                plugin.getSLF4JLogger().error("A Frontier change listener failed", e);
            }
        }
    }

    /** Loads (or creates) the three worlds of {@code row}. Main thread. */
    private void load(FrontierRow row) {
        worlds.overworld(row, settings());
        if (settings().nether()) {
            worlds.nether(row, settings());
        }
        if (settings().end().enabled()) {
            worlds.end(row, settings());
        }
        name(row);
    }

    private void name(FrontierRow row) {
        String modifier = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(modifierName(row.modifier()));
        var names = plugin.core().worlds();
        names.nameWorld(row.worldKey(), "Frontier · " + modifier);
        names.nameWorld(row.netherKey(), "The Nether");
        names.nameWorld(row.endKey(), "The End");
    }

    private void pregenerate(FrontierRow row) {
        if (!settings().pregenerate()) {
            return;
        }
        if (chunky == null) {
            plugin.getSLF4JLogger().warn("Chunky isn't installed: Frontier #{} generates as players explore", row.id());
            return;
        }
        World over = FrontierWorlds.loaded(row.worldKey());
        if (over != null) {
            chunky.start(over, settings().borderSize() / 2.0);
        }
        World nether = FrontierWorlds.loaded(row.netherKey());
        if (nether != null) {
            chunky.start(nether, settings().netherBorderSize() / 2.0);
        }
        World end = FrontierWorlds.loaded(row.endKey());
        if (end != null) {
            chunky.start(end, Math.min(settings().endBorderSize() / 2.0, 1000));
        }
        plugin.getSLF4JLogger().info("Pre-generating Frontier #{} with Chunky", row.id());
    }

    /** Deletes archives older than {@code archive-days}. */
    private void pruneArchives() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(settings().archiveDays()));
        async(() -> {
            for (FrontierRow row : repository.archived()) {
                if (row.closedAt().isPresent() && row.closedAt().get().isBefore(cutoff) && row.archivePath().isPresent()) {
                    try {
                        FrontierWorlds.delete(Path.of(row.archivePath().get()));
                        repository.setArchivePath(row.id(), null);
                        plugin.getSLF4JLogger().info("Deleted the archive of Frontier #{}", row.id());
                    } catch (IOException e) {
                        plugin.getSLF4JLogger().warn("Couldn't delete {}: {}", row.archivePath().get(), e.getMessage());
                    }
                }
            }
            return null;
        }).exceptionally(e -> {
            plugin.getSLF4JLogger().warn("Couldn't prune Frontier archives: {}", e.getMessage());
            return null;
        });
    }

    // ------------------------------------------------------------------ admin

    /** Moves the open Frontier's closing time by {@code days} (negative: sooner). */
    public void extend(int days, Consumer<String> report) {
        FrontierRow c = current;
        if (c == null || c.closesAt().isEmpty()) {
            report.accept("No Frontier is open.");
            return;
        }
        Instant closes = c.closesAt().get().plus(Duration.ofDays(days));
        async(() -> {
            repository.setClosesAt(c.id(), closes);
            return null;
        }).thenRun(() -> sync(() -> {
            if (current != null && current.id() == c.id()) {
                current = withCloses(current, closes);
                if (FrontierSchedule.phase(Instant.now(), closes, Duration.ofDays(settings().warningDays()))
                        == Phase.OPEN) {
                    warnedFor = -1;
                }
            }
            report.accept("Frontier #" + c.id() + " now closes " + closes);
        })).exceptionally(e -> {
            report.accept("Couldn't save: " + e.getMessage());
            return null;
        });
    }

    /** Opens the current End now (for testing, or to start the dragon fight early). */
    public void openEndNow(Consumer<String> report) {
        FrontierRow c = current;
        if (c == null) {
            report.accept("No Frontier is open.");
            return;
        }
        Instant now = Instant.now();
        async(() -> {
            repository.setEndOpensAt(c.id(), now);
            return null;
        }).thenRun(() -> sync(() -> {
            if (current != null && current.id() == c.id()) {
                FrontierRow r = current;
                current = new FrontierRow(r.id(), r.modifier(), r.seed(), r.chapter(), r.state(), r.createdAt(),
                        r.openedAt(), r.closesAt(), r.closedAt(), Optional.of(now), r.dragonKilledAt(), r.archivePath());
                endOpenFor = -1;
                endAnnouncements(current, now.plusSeconds(1));
            }
            report.accept("The End of Frontier #" + c.id() + " is open");
        }));
    }

    /** The dragon of the current End died for the first time; recorded once. Completes true if this was the first. */
    public CompletableFuture<Boolean> dragonKilled(FrontierRow row) {
        Instant now = Instant.now();
        return async(() -> repository.markDragonKilled(row.id(), now)).thenApply(first -> {
            if (first) {
                sync(() -> {
                    if (current != null && current.id() == row.id()) {
                        FrontierRow r = current;
                        current = new FrontierRow(r.id(), r.modifier(), r.seed(), r.chapter(), r.state(),
                                r.createdAt(), r.openedAt(), r.closesAt(), r.closedAt(), r.endOpensAt(),
                                Optional.of(now), r.archivePath());
                    }
                });
            }
            return first;
        });
    }

    private static FrontierRow withCloses(FrontierRow r, Instant closes) {
        return new FrontierRow(r.id(), r.modifier(), r.seed(), r.chapter(), r.state(), r.createdAt(), r.openedAt(),
                Optional.of(closes), r.closedAt(), r.endOpensAt(), r.dragonKilledAt(), r.archivePath());
    }

    /** Status lines for {@code /frontier status}. */
    public List<String> status() {
        FrontierRow c = current;
        FrontierRow p = preparing;
        Instant now = Instant.now();
        List<String> out = new java.util.ArrayList<>();
        out.add(c == null ? "Open: none" : "Open: #" + c.id() + " " + c.modifier().id() + ", closes in "
                + Durations.format(FrontierSchedule.until(now, c.closesAt().orElse(now))) + ", chapter "
                + c.chapter().orElse("none") + ", End " + (endOpen() ? "open" : c.endOpensAt()
                .map(t -> "opens in " + Durations.format(FrontierSchedule.until(now, t))).orElse("off"))
                + (c.dragonKilledAt().isPresent() ? " (dragon slain)" : ""));
        out.add(p == null ? "Preparing: none" : "Preparing: #" + p.id() + " " + p.modifier().id()
                + (chunky != null && FrontierWorlds.loaded(p.worldKey()) != null
                && chunky.running(FrontierWorlds.loaded(p.worldKey())) ? " (Chunky running)" : ""));
        out.add("Wildlands: beyond " + wildlands.innerRadius() + " blocks; Chunky: "
                + (chunky == null ? "not installed" : "ready") + "; worlds: " + plugin.worldHost().describe());
        return out;
    }

    // ------------------------------------------------------------------ travel

    /** {@code /frontier go}: a random safe spot in the Frontier's centre, outside the Wildlands. */
    public void travel(Player player) {
        FrontierRow c = current;
        World world = overworld();
        if (c == null || world == null) {
            plugin.lang().send(player, "frontier.closed");
            return;
        }
        if (plugin.pvp().inCombat(player.getUniqueId())) {
            plugin.lang().send(player, "pvp.no-teleport");
            return;
        }
        long now = System.currentTimeMillis();
        Long until = travelCooldown.get(player.getUniqueId());
        if (until != null && until > now && !player.hasPermission(us.rapidskies.horizons.travel.RtpService.NO_COOLDOWN)) {
            plugin.lang().send(player, "frontier.travel-cooldown",
                    Placeholder.unparsed("time", Durations.format(Duration.ofMillis(until - now))));
            return;
        }
        int half = settings().borderSize() / 2;
        int radius = wildlands.safeRadius(half, SAFE_MARGIN);
        plugin.rtp().search(player, world, 0, 0, 0, radius, false, "frontier.searching", spot -> {
            travelCooldown.put(player.getUniqueId(),
                    System.currentTimeMillis() + settings().travelCooldownSeconds() * 1000L);
            plugin.lang().send(player, "frontier.arrived", Placeholder.component("frontier", title(c)),
                    Placeholder.unparsed("x", Integer.toString(spot.getBlockX())),
                    Placeholder.unparsed("z", Integer.toString(spot.getBlockZ())));
        });
    }

    // ------------------------------------------------------------------ players

    // MONITOR: only logins that go ahead. Async thread.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        try {
            OptionalInt id = repository.logoutFrontier(event.getUniqueId());
            if (id.isPresent()) {
                loggedOutIn.put(event.getUniqueId(), id.getAsInt());
            } else {
                loggedOutIn.remove(event.getUniqueId());
            }
        } catch (SQLException e) {
            plugin.getSLF4JLogger().warn("Couldn't read where {} logged out: {}", event.getName(), e.getMessage());
        }
    }

    /** After rapid-core's spawn rules: a player whose Frontier has closed comes back at the spawn. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onSpawnLocation(AsyncPlayerSpawnLocationEvent event) {
        UUID id = event.getConnection().getProfile().getId();
        Integer was = id == null ? null : loggedOutIn.get(id);
        if (was == null || event.isNewPlayer()) {
            return;
        }
        if (!ready) {
            // Right after a restart the Frontier worlds may not be loaded yet, and Paper would
            // put them at the Heartland's spawn: the Horizons spawn is better. Nothing has closed.
            event.setSpawnLocation(plugin.core().worlds().spawn());
            return;
        }
        FrontierRow c = current;
        if (c != null && c.id() == was) {
            return;
        }
        event.setSpawnLocation(plugin.core().worlds().spawn());
        movedHome.add(id);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (movedHome.remove(player.getUniqueId())) {
            plugin.lang().send(player, "frontier.closed-while-away");
        }
        FrontierRow c = current;
        if (c != null && c.closesAt().isPresent() && FrontierSchedule.phase(Instant.now(), c.closesAt().get(),
                Duration.ofDays(settings().warningDays())) == Phase.WARNING) {
            plugin.lang().send(player, "frontier.reset-reminder", Placeholder.component("frontier", title(c)),
                    resetLeft(c));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        FrontierRow c = current;
        if (c == null || place(player.getWorld()) != Place.FRONTIER) {
            return;
        }
        player.showTitle(Title.title(title(c), plugin.lang().get("frontier.modifier." + c.modifier().id() + ".about"),
                Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(4), Duration.ofSeconds(1))));
        if (c.closesAt().isPresent() && FrontierSchedule.phase(Instant.now(), c.closesAt().get(),
                Duration.ofDays(settings().warningDays())) == Phase.WARNING) {
            plugin.lang().send(player, "frontier.reset-reminder", Placeholder.component("frontier", title(c)),
                    resetLeft(c));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        FrontierRow c = current;
        Integer now = c != null && inFrontier(player.getWorld()) ? c.id() : null;
        Integer was = loggedOutIn.remove(id);
        travelCooldown.remove(id);
        if (java.util.Objects.equals(now, was)) {
            return;
        }
        async(() -> {
            repository.setLogoutFrontier(id, now);
            return null;
        }).exceptionally(e -> {
            plugin.getSLF4JLogger().warn("Couldn't save where {} logged out: {}", player.getName(), e.getMessage());
            return null;
        });
    }

    // ------------------------------------------------------------------ threads

    private <T> CompletableFuture<T> async(us.rapidskies.common.db.Database.SqlSupplier<T> work) {
        return plugin.core().database().async(work);
    }

    private void sync(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }
}
