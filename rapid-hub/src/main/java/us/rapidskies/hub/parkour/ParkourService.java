package us.rapidskies.hub.parkour;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.scheduler.BukkitTask;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.common.parkour.ParkourTimes;
import us.rapidskies.hub.HubSettings;
import us.rapidskies.hub.RapidHub;
import us.rapidskies.core.display.Labels;
import us.rapidskies.hub.parkour.Layout.Trigger;

/**
 * Hub parkour (PLAN.md §7) on generated courses: every run builds its own random layout
 * ({@link CourseGenerator}) in a lane of the parkour world, a void world rapid-core creates
 * on the hub. Players start from the hotbar, {@code /parkour start} or a course's entrance
 * plate in the hub; the clock starts when they jump off the start platform, every
 * checkpoint platform must be touched in order, and the time stops on the finish platform.
 * Courses have a fixed length and checkpoint count, so times on the leaderboards compare
 * like with like even though no two layouts are the same.
 *
 * <p>A run ends if the player flies, glides, changes gamemode or world, or is teleported by
 * anything but the run itself, so a time is always one honest go. Falling below the
 * stretch they're on puts the player back at their last checkpoint with the clock still
 * running, or back on the start platform with the clock reset. When a run ends its blocks
 * are removed and the lane is free for the next player.
 */
public final class ParkourService implements Listener {

    /** Runs left this long are dropped (AFK on the course). */
    private static final long MAX_RUN_MILLIS = 60 * 60 * 1000L;
    private static final long TIMER_PERIOD_TICKS = 2;
    /** How long a finisher stands on the finish before going back to the hub. */
    private static final long FINISH_HOME_TICKS = 50;
    /** Blocks below a stretch's lowest block that count as a fall. */
    private static final int FALL_MARGIN = 3;
    /** East, along the course. */
    private static final float FORWARD_YAW = -90f;

    private static final Sound START = Sound.sound(Key.key("block.note_block.pling"), Sound.Source.PLAYER, 0.8f, 1.2f);
    private static final Sound GO = Sound.sound(Key.key("block.note_block.pling"), Sound.Source.PLAYER, 0.8f, 1.8f);
    private static final Sound CHECKPOINT = Sound.sound(Key.key("entity.experience_orb.pickup"), Sound.Source.PLAYER, 0.8f, 1.0f);
    private static final Sound FINISH = Sound.sound(Key.key("entity.player.levelup"), Sound.Source.PLAYER, 0.8f, 1.0f);
    private static final Sound FAIL = Sound.sound(Key.key("entity.enderman.teleport"), Sound.Source.PLAYER, 0.5f, 1.2f);

    /** Jump blocks per stretch, from the build palette (PLAN.md §2); all exist in 1.20. */
    private static final List<Material> JUMP_BLOCKS = List.of(Material.YELLOW_WOOL, Material.QUARTZ_BLOCK,
            Material.YELLOW_TERRACOTTA, Material.WHITE_WOOL, Material.SPONGE, Material.COAL_BLOCK);

    /** A player's run, its lane and where a fall sends them back to. */
    private static final class Active {
        final Run run;
        final int lane;
        final BlockPos origin;
        final Map<BlockPos, Trigger> triggers;
        Location respawn;
        int lastMissed = -1;

        Active(Run run, int lane, BlockPos origin, Location respawn) {
            this.run = run;
            this.lane = lane;
            this.origin = origin;
            this.triggers = run.layout().triggers();
            this.respawn = respawn;
        }

        /** {@code feet} relative to this lane's origin. */
        BlockPos relative(Location at) {
            return new BlockPos(at.getBlockX() - origin.x(), at.getBlockY() - origin.y(), at.getBlockZ() - origin.z());
        }
    }

    private record Place(UUID world, BlockPos pos) {
    }

    private final RapidHub hub;
    private final ParkourTimes times;
    private final Lanes lanes;
    private final Map<String, Course> courses = new LinkedHashMap<>();
    private final Map<Place, Course> entrances = new HashMap<>();
    private final Map<UUID, Active> runs = new HashMap<>();
    /** Players this service is teleporting right now, whose teleport mustn't end their run. */
    private final Set<UUID> moving = new HashSet<>();
    private World world;
    private BukkitTask timer;

    public ParkourService(RapidHub hub, ParkourTimes times) {
        this.hub = hub;
        this.times = times;
        this.lanes = new Lanes(hub.getDataPath().resolve("parkour-lanes.txt"), hub.getSLF4JLogger());
    }

    private Lang lang() {
        return hub.lang();
    }

    /** Finds the parkour world, clears what a crash left in it, and indexes the entrances. */
    public void load(HubSettings settings) {
        courses.clear();
        entrances.clear();
        world = Labels.world(settings.parkourWorld());
        if (world == null) {
            hub.getSLF4JLogger().error("parkour.world: '{}' isn't loaded, so the parkour is closed. Add it to "
                    + "rapid-core's worlds (create: void) on the hub.", settings.parkourWorld());
            return;
        }
        lanes.leftovers().forEach((lane, blocks) -> {
            blocks.forEach(p -> world.getBlockAt(p.x(), p.y(), p.z()).setType(Material.AIR, false));
            hub.getSLF4JLogger().info("Cleared parkour lane {} left over from the last run of the server", lane);
        });
        World hubWorld = Labels.world(HubSettings.DEFAULT_WORLD);
        for (Course course : settings.courses()) {
            courses.put(course.id(), course);
            course.entrance().ifPresent(pos -> {
                if (hubWorld != null) {
                    entrances.put(new Place(hubWorld.getUID(), pos), course);
                }
            });
            hub.getSLF4JLogger().info("Parkour course {}: {} blocks, {} checkpoints", course.id(), course.length(),
                    course.checkpoints());
        }
    }

    /** Whether a course exists and its world is loaded. */
    public boolean isOpen(String course) {
        return world != null && courses.containsKey(course);
    }

    /** The first open course (the one the hotbar item and a bare {@code /parkour start} use). */
    public Optional<Course> firstOpen() {
        return world == null ? Optional.empty() : courses.values().stream().findFirst();
    }

    public void start() {
        timer = Bukkit.getScheduler().runTaskTimer(hub, this::showTimers, TIMER_PERIOD_TICKS, TIMER_PERIOD_TICKS);
    }

    /** Ends every run and stops the timer (plugin stop). */
    public void stop() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
        endAll();
    }

    /** Ends every run without saving and sends the runners home (reload: the courses may have changed). */
    public void endAll() {
        for (UUID id : List.copyOf(runs.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                end(p, "parkour.cancelled", true);
            } else {
                release(runs.remove(id));
            }
        }
    }

    public boolean inRun(Player player) {
        return runs.containsKey(player.getUniqueId());
    }

    private static TagResolver course(Lang lang, Course course) {
        return Placeholder.component("course", name(lang, course.id()));
    }

    /** A course's display name: lang {@code parkour.course-name.<id>}, else its id. */
    public static Component name(Lang lang, String courseId) {
        String key = "parkour.course-name." + courseId;
        return lang.has(key) ? lang.get(key) : Component.text(courseId);
    }

    private Location at(BlockPos origin, BlockPos centre) {
        return new Location(world, origin.x() + centre.x() + 0.5, origin.y() + centre.y() + 1,
                origin.z() + centre.z() + 0.5, FORWARD_YAW, 0);
    }

    // ------------------------------------------------------------------ runs

    /** Builds a fresh layout of {@code course} for the player and puts them on its start platform. */
    public void join(Player player, Course course) {
        if (!isOpen(course.id())) {
            lang().send(player, "parkour.closed");
            return;
        }
        // Staff mode flies and has its own hotbar; creative and spectator can fly too.
        if ((player.getGameMode() != GameMode.SURVIVAL && player.getGameMode() != GameMode.ADVENTURE)
                || hub.core().staffMode().isActive(player)) {
            lang().send(player, "parkour.not-now");
            return;
        }
        Active previous = runs.remove(player.getUniqueId());
        if (previous != null) {
            release(previous);
        }
        Layout layout = CourseGenerator.generate(course, ThreadLocalRandom.current().nextLong());
        int lane = lanes.claim(l -> absolute(layout, Lanes.origin(l)));
        BlockPos origin = Lanes.origin(lane);
        for (Layout.Block b : layout.blocks()) {
            world.getBlockAt(origin.x() + b.pos().x(), origin.y() + b.pos().y(), origin.z() + b.pos().z())
                    .setType(material(b), false);
        }
        Location start = at(origin, layout.start().centre());
        Active active = new Active(new Run(course, layout), lane, origin, start);
        runs.put(player.getUniqueId(), active);
        teleport(player, start);
        hub.hotbar().giveParkour(player);
        hub.jump().refresh(player);
        lang().send(player, "parkour.started", course(lang(), course),
                Placeholder.unparsed("length", Integer.toString(course.length())),
                Placeholder.unparsed("total", Integer.toString(layout.checkpoints().size())));
        player.playSound(START, Sound.Emitter.self());
    }

    private static List<BlockPos> absolute(Layout layout, BlockPos origin) {
        List<BlockPos> out = new ArrayList<>(layout.blocks().size());
        for (Layout.Block b : layout.blocks()) {
            out.add(new BlockPos(origin.x() + b.pos().x(), origin.y() + b.pos().y(), origin.z() + b.pos().z()));
        }
        return out;
    }

    private static Material material(Layout.Block block) {
        return switch (block.piece()) {
            case START -> Material.POLISHED_BLACKSTONE;
            case CHECKPOINT -> Material.GOLD_BLOCK;
            case FINISH -> Material.SEA_LANTERN;
            case PLATE -> Material.LIGHT_WEIGHTED_PRESSURE_PLATE;
            case JUMP -> JUMP_BLOCKS.get(block.stretch() % JUMP_BLOCKS.size());
        };
    }

    /** Removes a run's blocks and frees its lane. */
    private void release(Active active) {
        if (active == null) {
            return;
        }
        for (BlockPos p : absolute(active.run.layout(), active.origin)) {
            world.getBlockAt(p.x(), p.y(), p.z()).setType(Material.AIR, false);
        }
        lanes.release(active.lane);
    }

    /** Sends a player back to the hub's spawn (rapid-core). */
    private void home(Player player) {
        hub.core().worlds().teleport(player);
    }

    /**
     * Ends a run without a time and gives the hub hotbar back. {@code sendHome}: off the
     * course and back to the hub, unless something else is already moving them.
     */
    private void end(Player player, String messageKey, boolean sendHome) {
        Active active = runs.remove(player.getUniqueId());
        if (active == null) {
            return;
        }
        hub.hotbar().give(player);
        hub.jump().refresh(player);
        player.sendActionBar(Component.empty());
        if (messageKey != null) {
            lang().send(player, messageKey);
        }
        if (sendHome && player.getWorld().equals(world)) {
            home(player);
        }
        release(active);
    }

    private void finish(Player player, Active active) {
        long millis = active.run.elapsedMillis(System.nanoTime());
        Course course = active.run.course();
        runs.remove(player.getUniqueId());
        hub.hotbar().give(player);
        hub.jump().refresh(player);
        player.playSound(FINISH, Sound.Emitter.self());
        String time = TimeFormat.run(millis);
        lang().send(player, "parkour.finished", course(lang(), course), Placeholder.unparsed("time", time));
        player.showTitle(Title.title(lang().get("parkour.finish-title"),
                lang().get("parkour.finish-subtitle", Placeholder.unparsed("time", time)),
                Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(2000), Duration.ofMillis(500))));
        // A moment on the finish platform, then home; the lane is freed either way.
        UUID id = player.getUniqueId();
        Bukkit.getScheduler().runTaskLater(hub, () -> {
            Player online = Bukkit.getPlayer(id);
            if (online != null && online.getWorld().equals(world) && !runs.containsKey(id)) {
                home(online);
            }
            release(active);
        }, FINISH_HOME_TICKS);

        String name = player.getName();
        int ms = (int) Math.min(Integer.MAX_VALUE, Math.max(1, millis));
        hub.core().database().async(() -> times.record(course.id(), id, ms, Instant.now())).whenComplete((result, error) ->
                Bukkit.getScheduler().runTask(hub, () -> {
                    Player online = Bukkit.getPlayer(id);
                    if (error != null) {
                        hub.getSLF4JLogger().warn("Couldn't save {}'s parkour time on {}", name, course.id(), error);
                        if (online != null) {
                            lang().send(online, "parkour.save-failed");
                        }
                        return;
                    }
                    announce(online, name, course, result);
                    hub.leaderboards().refresh(course.id());
                }));
    }

    private void announce(Player player, String name, Course course, ParkourTimes.Result result) {
        TagResolver c = course(lang(), course);
        if (player != null) {
            TagResolver best = TagResolver.resolver(c,
                    Placeholder.unparsed("time", TimeFormat.run(result.allTime().timeMs())),
                    Placeholder.unparsed("rank", Integer.toString(result.allTime().rank())));
            if (result.personalBest()) {
                lang().send(player, result.previousBest().isPresent() ? "parkour.personal-best" : "parkour.first-finish",
                        best);
            } else {
                lang().send(player, "parkour.not-best", best);
                if (result.weeklyBest()) {
                    lang().send(player, "parkour.weekly-best",
                            Placeholder.unparsed("time", TimeFormat.run(result.weekly().timeMs())),
                            Placeholder.unparsed("rank", Integer.toString(result.weekly().rank())));
                }
            }
        }
        if (result.personalBest() && result.allTime().rank() == 1) {
            Component record = lang().get("parkour.record", c, Placeholder.unparsed("player", name),
                    Placeholder.unparsed("time", TimeFormat.run(result.allTime().timeMs())));
            Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(record));
        }
    }

    /** Puts the player back at their last checkpoint (or the start platform, with the clock reset). */
    public void backToCheckpoint(Player player) {
        Active active = runs.get(player.getUniqueId());
        if (active == null) {
            lang().send(player, "parkour.not-running");
            return;
        }
        if (active.run.reached() == 0) {
            restart(player);
            return;
        }
        teleport(player, active.respawn);
    }

    /** Back to the start platform of the same layout; the clock starts again when they jump off. */
    public void restart(Player player) {
        Active active = runs.get(player.getUniqueId());
        if (active == null) {
            lang().send(player, "parkour.not-running");
            return;
        }
        active.run.reset();
        active.lastMissed = -1;
        active.respawn = at(active.origin, active.run.layout().start().centre());
        teleport(player, active.respawn);
        player.playSound(START, Sound.Emitter.self());
    }

    public void leave(Player player) {
        if (!inRun(player)) {
            lang().send(player, "parkour.not-running");
            return;
        }
        end(player, "parkour.left", true);
    }

    private void teleport(Player player, Location to) {
        moving.add(player.getUniqueId());
        try {
            player.setFallDistance(0);
            player.setVelocity(new org.bukkit.util.Vector());
            player.teleport(to, PlayerTeleportEvent.TeleportCause.PLUGIN);
        } finally {
            moving.remove(player.getUniqueId());
        }
    }

    private void showTimers() {
        long now = System.nanoTime();
        for (Map.Entry<UUID, Active> e : List.copyOf(runs.entrySet())) {
            Player player = Bukkit.getPlayer(e.getKey());
            if (player == null) {
                continue;
            }
            Active active = e.getValue();
            Run run = active.run;
            if (!run.started()) {
                player.sendActionBar(lang().get("parkour.ready", course(lang(), run.course())));
                continue;
            }
            long elapsed = run.elapsedMillis(now);
            if (elapsed > MAX_RUN_MILLIS) {
                end(player, "parkour.timed-out", true);
                continue;
            }
            int length = run.layout().length();
            int distance = Math.clamp(player.getLocation().getBlockX() - active.origin.x(), 0, length);
            player.sendActionBar(lang().get("parkour.timer", course(lang(), run.course()),
                    Placeholder.unparsed("time", TimeFormat.timer(elapsed)),
                    Placeholder.unparsed("distance", Integer.toString(distance)),
                    Placeholder.unparsed("length", Integer.toString(length)),
                    Placeholder.unparsed("reached", Integer.toString(run.reached())),
                    Placeholder.unparsed("total", Integer.toString(run.layout().checkpoints().size()))));
        }
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedPosition()) {
            return;
        }
        Player player = event.getPlayer();
        Location to = event.getTo();
        Active active = runs.get(player.getUniqueId());
        if (active == null) {
            if (!event.hasChangedBlock()) {
                return;
            }
            if (to.getWorld().equals(world)) {
                // Not on a run (staff, a finisher on their way home): don't fall forever.
                if (to.getY() < Lanes.BASE_Y - CourseGenerator.MAX_RISE - 2 * FALL_MARGIN) {
                    home(player);
                }
                return;
            }
            Course entrance = entrances.get(new Place(to.getWorld().getUID(),
                    new BlockPos(to.getBlockX(), to.getBlockY(), to.getBlockZ())));
            if (entrance != null && !hub.core().staffMode().isActive(player)
                    && (player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE)) {
                join(player, entrance);
            }
            return;
        }
        if (!to.getWorld().equals(world)) {
            return; // the world change ends it
        }
        if (player.isFlying() || player.isGliding()) {
            end(player, "parkour.cancelled-flying", true);
            return;
        }
        Run run = active.run;
        BlockPos feet = active.relative(to);
        if (!run.started()) {
            if (!run.layout().start().over(feet)) {
                run.start(System.nanoTime());
                player.playSound(GO, Sound.Emitter.self());
            }
            return;
        }
        if (to.getY() < active.origin.y() + run.layout().floor(run.reached()) - FALL_MARGIN) {
            player.playSound(FAIL, Sound.Emitter.self());
            backToCheckpoint(player);
            return;
        }
        if (!event.hasChangedBlock()) {
            return;
        }
        Trigger trigger = active.triggers.get(feet);
        if (trigger == null) {
            return;
        }
        switch (run.step(trigger)) {
            case CHECKPOINT -> {
                active.respawn = at(active.origin, run.layout().checkpoints().get(trigger.index()).centre());
                player.playSound(CHECKPOINT, Sound.Emitter.self());
                lang().send(player, "parkour.checkpoint",
                        Placeholder.unparsed("number", Integer.toString(trigger.index() + 1)),
                        Placeholder.unparsed("total", Integer.toString(run.layout().checkpoints().size())),
                        Placeholder.unparsed("time", TimeFormat.run(run.elapsedMillis(System.nanoTime()))));
            }
            case MISSED -> {
                if (active.lastMissed != run.next()) {
                    active.lastMissed = run.next();
                    lang().send(player, "parkour.missed",
                            Placeholder.unparsed("number", Integer.toString(run.next() + 1)));
                }
            }
            case FINISHED -> finish(player, active);
            case NONE -> {
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (!moving.contains(event.getPlayer().getUniqueId())) {
            // Something else is moving them already (/spawn, staff): don't send them home too.
            end(event.getPlayer(), "parkour.cancelled", false);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        if (event.isFlying()) {
            end(event.getPlayer(), "parkour.cancelled-flying", true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        end(event.getPlayer(), "parkour.cancelled", false);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        if (!event.getPlayer().getWorld().equals(world)) {
            end(event.getPlayer(), "parkour.cancelled", false);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        release(runs.remove(event.getPlayer().getUniqueId()));
    }

    // ------------------------------------------------------------------ lookups for /parkour

    /** The player's all-time best and rank on a course, looked up off the main thread. */
    public void standing(Player player, String courseId, Consumer<Optional<ParkourTimes.Standing>> then) {
        UUID id = player.getUniqueId();
        hub.core().database().async(() -> times.standing(courseId, ParkourTimes.ALL_TIME, id)).whenComplete((s, error) ->
                Bukkit.getScheduler().runTask(hub, () -> {
                    if (error != null) {
                        hub.getSLF4JLogger().warn("Couldn't read {}'s parkour times", player.getName(), error);
                        return;
                    }
                    if (player.isOnline()) {
                        then.accept(s);
                    }
                }));
    }

    /** The top entries of a board, looked up off the main thread. */
    public void top(String courseId, String period, int size, Consumer<List<ParkourTimes.Entry>> then) {
        hub.core().database().async(() -> times.top(courseId, period, size)).whenComplete((list, error) ->
                Bukkit.getScheduler().runTask(hub, () -> {
                    if (error != null) {
                        hub.getSLF4JLogger().warn("Couldn't read the {} parkour board", courseId, error);
                        return;
                    }
                    then.accept(list);
                }));
    }
}
