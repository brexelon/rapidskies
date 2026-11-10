package us.rapidskies.horizons.skyborne;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import us.rapidskies.common.time.Durations;
import us.rapidskies.horizons.HorizonsSettings;
import us.rapidskies.horizons.RapidHorizons;
import us.rapidskies.horizons.chapter.ChapterGoal;
import us.rapidskies.horizons.event.Announcer;
import us.rapidskies.horizons.event.EventRewards;
import us.rapidskies.horizons.frontier.FrontierService;
import us.rapidskies.horizons.frontier.WildlandsZone;

/**
 * Skyborne events (PLAN.md §5.2): a sky fortress ({@link FortressLayout}) appears over the
 * open Frontier every few hours, announced network-wide ten minutes ahead with its
 * coordinates. It's a cooperative PvE dungeon: waves of mobs, then the Sky Warden, all
 * scaled to the number of players fighting ({@link SkyborneScaling}). Everyone who dealt
 * enough damage is rewarded once ({@link EventRewards}) and ticks the Chapter's
 * {@code skyborne} entry. Then the fortress drifts away (its blocks are removed).
 *
 * <ul>
 *   <li>Players reach it through an updraft: a column of cloud particles from the ground
 *       that lifts anyone standing in it and sets them down on the landing pad.
 *       {@code /skyborne go} takes players to its foot.</li>
 *   <li>It hovers in the Frontier's safe centre (never the Wildlands), above the terrain.
 *       Its blocks can't be broken, burnt or blown up; anyone falling off gets Slow Falling.</li>
 *   <li>Its position is journalled ({@value #JOURNAL}) before building, so a crash mid-event
 *       never leaves a fortress behind: the next start removes it.</li>
 * </ul>
 */
public final class SkyborneService implements Listener {

    public static final String ADMIN = "rapid.horizons.skyborne.admin";
    static final String JOURNAL = "skyborne-fortress.txt";
    private static final int BLOCKS_PER_TICK = 1500;
    private static final double UPDRAFT_RADIUS = 1.6;

    public enum State {
        IDLE, ANNOUNCED, BUILDING, ACTIVE, BOSS, ENDING
    }

    private final RapidHorizons plugin;
    private final FrontierService frontier;
    private final Announcer announcer;
    private final EventRewards rewards;
    private final NamespacedKey mobKey;
    private final Path journal;
    private final Random random = new Random();
    private final List<FortressLayout.Placement> layout = FortressLayout.blocks();

    private State state = State.IDLE;
    private Instant nextAt;
    private Instant startsAt;
    private Instant startedAt;
    private Instant clearAt;
    private String eventId;
    private World world;
    private int ox;
    private int oy;
    private int oz;
    private int wave;
    private final Set<UUID> mobs = new HashSet<>();
    private UUID boss;
    private BossBar bar;
    private final Set<UUID> barViewers = new HashSet<>();
    private final Set<UUID> participants = new HashSet<>();
    private final Map<UUID, Double> damage = new ConcurrentHashMap<>();
    private final Set<Long> reminded = new HashSet<>();
    private final Map<UUID, Long> goCooldown = new ConcurrentHashMap<>();
    private BukkitTask ticker;
    private BukkitTask updraft;
    private BukkitTask blocks;

    public SkyborneService(RapidHorizons plugin, FrontierService frontier, Announcer announcer, EventRewards rewards) {
        this.plugin = plugin;
        this.frontier = frontier;
        this.announcer = announcer;
        this.rewards = rewards;
        this.mobKey = new NamespacedKey(plugin, "skyborne");
        this.journal = plugin.getDataPath().resolve(JOURNAL);
    }

    private HorizonsSettings.Skyborne settings() {
        return plugin.settings().skyborne();
    }

    public State state() {
        return state;
    }

    public void start() {
        scheduleNext();
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        updraft = Bukkit.getScheduler().runTaskTimer(plugin, this::updraftTick, 2L, 2L);
    }

    /** Removes the fortress at once (plugin disabling); the journal makes sure it's gone. */
    public void stop() {
        if (ticker != null) {
            ticker.cancel();
        }
        if (updraft != null) {
            updraft.cancel();
        }
        if (blocks != null) {
            blocks.cancel();
        }
        if (state != State.IDLE && state != State.ANNOUNCED && world != null) {
            removeMobs();
            hideBar();
            for (FortressLayout.Placement p : layout) {
                clearOne(p);
            }
            releaseChunks();
            deleteJournal();
        }
        state = State.IDLE;
    }

    private void scheduleNext() {
        nextAt = SkyborneScaling.next(Instant.now(), Duration.ofMinutes(settings().intervalMinutes()),
                Duration.ofMinutes(settings().jitterMinutes()), random);
    }

    // ------------------------------------------------------------------ the clock

    private void tick() {
        Instant now = Instant.now();
        try {
            switch (state) {
                case IDLE -> {
                    if (Files.exists(journal)) {
                        clearLeftover();
                        return;
                    }
                    if (settings().enabled() && now.isAfter(nextAt.minus(Duration.ofMinutes(settings().announceMinutes())))) {
                        announce(now, nextAt.isAfter(now.plus(Duration.ofMinutes(settings().announceMinutes())))
                                ? nextAt : now.plus(Duration.ofMinutes(settings().announceMinutes())), false);
                    }
                }
                case ANNOUNCED -> announced(now);
                case BUILDING -> {
                    // blocks task is running
                }
                case ACTIVE, BOSS -> fight(now);
                case ENDING -> {
                    if (!now.isBefore(clearAt)) {
                        clear();
                    }
                }
            }
        } catch (RuntimeException e) {
            plugin.getSLF4JLogger().error("Skyborne event failed; removing the fortress", e);
            abort();
        }
    }

    /** Picks a spot over the Frontier and tells the network. False (and postponed) if it can't happen now. */
    private boolean announce(Instant now, Instant at, boolean forced) {
        World w = frontier.overworld();
        int online = Bukkit.getOnlinePlayers().size();
        if (w == null || (!forced && online < settings().minPlayers())) {
            nextAt = now.plus(Duration.ofMinutes(10));
            return false;
        }
        int half = plugin.settings().frontier().borderSize() / 2;
        WildlandsZone zone = frontier.wildlands();
        int radius = Math.max(64, zone.safeRadius(half, 96));
        int r = 64 + random.nextInt(Math.max(1, radius - 64));
        double angle = random.nextDouble() * Math.PI * 2;
        world = w;
        ox = (int) Math.round(Math.cos(angle) * r);
        oz = (int) Math.round(Math.sin(angle) * r);
        oy = 0;
        startsAt = at;
        eventId = Long.toString(now.toEpochMilli(), 36);
        state = State.ANNOUNCED;
        reminded.clear();
        announcer.network(plugin.lang().get("skyborne.announce", where(),
                Placeholder.unparsed("time", Durations.format(Duration.between(now, at).plusSeconds(1)))));
        plugin.getSLF4JLogger().info("Skyborne fortress {} announced over {} {} for {}", eventId, ox, oz, at);
        return true;
    }

    private TagResolver where() {
        return TagResolver.resolver(Placeholder.unparsed("x", Integer.toString(ox)),
                Placeholder.unparsed("z", Integer.toString(oz)));
    }

    private void announced(Instant now) {
        long minutesLeft = Duration.between(now, startsAt).toMinutes();
        if ((minutesLeft == 4 || minutesLeft == 0) && reminded.add(minutesLeft) && now.isBefore(startsAt)) {
            announcer.local(plugin.lang().get("skyborne.reminder", where(),
                    Placeholder.unparsed("time", Durations.format(Duration.between(now, startsAt).plusSeconds(1)))));
        }
        if (now.isBefore(startsAt)) {
            return;
        }
        if (frontier.overworld() != world || world == null) {
            // The Frontier rotated meanwhile.
            state = State.IDLE;
            scheduleNext();
            return;
        }
        build();
    }

    // ------------------------------------------------------------------ building

    private void build() {
        state = State.BUILDING;
        List<long[]> chunks = new ArrayList<>();
        for (int cx = (ox + FortressLayout.MIN_X) >> 4; cx <= (ox + FortressLayout.MAX_X) >> 4; cx++) {
            for (int cz = (oz + FortressLayout.MIN_Z) >> 4; cz <= (oz + FortressLayout.MAX_Z) >> 4; cz++) {
                chunks.add(new long[] {cx, cz});
            }
        }
        AtomicInteger left = new AtomicInteger(chunks.size());
        for (long[] c : chunks) {
            world.getChunkAtAsync((int) c[0], (int) c[1], true).thenAccept(chunk -> {
                world.addPluginChunkTicket((int) c[0], (int) c[1], plugin);
                if (left.decrementAndGet() == 0) {
                    Bukkit.getScheduler().runTask(plugin, this::place);
                }
            });
        }
    }

    private void place() {
        if (state != State.BUILDING) {
            return;
        }
        int ground = world.getMinHeight();
        for (int dx = FortressLayout.MIN_X; dx <= FortressLayout.MAX_X; dx += 4) {
            for (int dz = FortressLayout.MIN_Z; dz <= FortressLayout.MAX_Z; dz += 4) {
                ground = Math.max(ground, world.getHighestBlockYAt(ox + dx, oz + dz, HeightMap.MOTION_BLOCKING));
            }
        }
        int top = world.getMaxHeight() - FortressLayout.MAX_Y - 4;
        oy = Math.min(top, Math.max(settings().minHeight(), ground - FortressLayout.MIN_Y + 12));
        writeJournal();
        Iterator<FortressLayout.Placement> it = layout.iterator();
        blocks = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (int i = 0; i < BLOCKS_PER_TICK && it.hasNext(); i++) {
                FortressLayout.Placement p = it.next();
                Block b = world.getBlockAt(ox + p.dx(), oy + p.dy(), oz + p.dz());
                // Only into air: the fortress never replaces terrain, so removing it can't hurt any.
                if (b.getType().isAir()) {
                    Material m = Material.matchMaterial(p.material());
                    if (m != null) {
                        b.setType(m, false);
                    }
                }
            }
            if (!it.hasNext()) {
                blocks.cancel();
                blocks = null;
                arrived();
            }
        }, 1L, 1L);
    }

    private void arrived() {
        state = State.ACTIVE;
        startedAt = Instant.now();
        wave = 0;
        participants.clear();
        damage.clear();
        world.playSound(new Location(world, ox, oy, oz), Sound.ENTITY_ENDER_DRAGON_FLAP, 4f, 0.6f);
        announcer.network(plugin.lang().get("skyborne.arrived", where(),
                Placeholder.unparsed("minutes", Integer.toString(settings().timeLimitMinutes()))));
    }

    // ------------------------------------------------------------------ the fight

    private boolean inside(Location at) {
        if (world == null || at.getWorld() != world) {
            return false;
        }
        return FortressLayout.inside(at.getBlockX() - ox, at.getBlockY() - oy, at.getBlockZ() - oz);
    }

    private List<Player> playersInside() {
        List<Player> out = new ArrayList<>();
        for (Player p : world.getPlayers()) {
            if (inside(p.getLocation()) && p.getGameMode() != GameMode.SPECTATOR) {
                out.add(p);
            }
        }
        return out;
    }

    private void fight(Instant now) {
        List<Player> here = playersInside();
        for (Player p : here) {
            participants.add(p.getUniqueId());
        }
        mobs.removeIf(id -> {
            Entity e = Bukkit.getEntity(id);
            if (e == null || e.isDead() || !e.isValid()) {
                return true;
            }
            if (e.getLocation().getY() < oy + FortressLayout.MIN_Y - 4) {
                e.remove();
                return true;
            }
            return false;
        });
        if (now.isAfter(startedAt.plus(Duration.ofMinutes(settings().timeLimitMinutes())))) {
            end(false);
            return;
        }
        if (state == State.ACTIVE) {
            if (mobs.isEmpty() && !here.isEmpty()) {
                if (wave < settings().waves()) {
                    spawnWave(here.size());
                } else {
                    spawnBoss(here.size());
                }
            }
            return;
        }
        Entity b = boss == null ? null : Bukkit.getEntity(boss);
        if (b == null || b.isDead() || !b.isValid()) {
            end(true);
            return;
        }
        if (b instanceof LivingEntity living && bar != null) {
            AttributeInstance max = living.getAttribute(Attribute.MAX_HEALTH);
            double full = max == null ? living.getHealth() : max.getValue();
            bar.progress((float) Math.max(0, Math.min(1, living.getHealth() / full)));
        }
        showBar(here);
    }

    private void spawnWave(int players) {
        wave++;
        int size = settings().scaling().waveSize(wave, players);
        List<int[]> spots = FortressLayout.spawnSpots();
        List<String> pool = settings().waveMobs();
        for (int i = 0; i < size; i++) {
            int[] s = spots.get(random.nextInt(spots.size()));
            EntityType type = Registry.ENTITY_TYPE.get(NamespacedKey.minecraft(pool.get(random.nextInt(pool.size()))));
            if (type == null || type.getEntityClass() == null || !type.isAlive()) {
                continue;
            }
            Location at = new Location(world, ox + s[0] + 0.5, oy + s[1], oz + s[2] + 0.5);
            Entity e = world.spawn(at, type.getEntityClass(), CreatureSpawnEvent.SpawnReason.CUSTOM, this::prepareMob);
            mobs.add(e.getUniqueId());
        }
        for (Player p : playersInside()) {
            plugin.lang().send(p, "skyborne.wave", Placeholder.unparsed("wave", Integer.toString(wave)),
                    Placeholder.unparsed("waves", Integer.toString(settings().waves())));
        }
    }

    private void prepareMob(Entity e) {
        e.getPersistentDataContainer().set(mobKey, PersistentDataType.BYTE, (byte) 1);
        e.setPersistent(true);
        if (e instanceof Mob mob) {
            mob.setRemoveWhenFarAway(false);
            EntityEquipment eq = mob.getEquipment();
            // A helmet keeps undead from burning in the sky's sunlight.
            eq.setHelmet(new ItemStack(Material.GOLDEN_HELMET));
            eq.setDropChance(EquipmentSlot.HEAD, 0f);
        }
    }

    private void spawnBoss(int players) {
        double health = settings().scaling().bossHealth(players);
        Location at = new Location(world, ox + 0.5, oy + 1, oz + 0.5);
        WitherSkeleton warden = world.spawn(at, WitherSkeleton.class, CreatureSpawnEvent.SpawnReason.CUSTOM, w -> {
            prepareMob(w);
            w.customName(plugin.lang().get("skyborne.boss-name"));
            w.setCustomNameVisible(true);
            w.setGlowing(true);
            AttributeInstance max = w.getAttribute(Attribute.MAX_HEALTH);
            if (max != null) {
                max.setBaseValue(health);
            }
            w.setHealth(health);
            w.getEquipment().setItemInMainHand(new ItemStack(Material.STONE_SWORD));
            w.getEquipment().setItemInMainHandDropChance(0f);
        });
        boss = warden.getUniqueId();
        mobs.add(boss);
        state = State.BOSS;
        bar = BossBar.bossBar(plugin.lang().get("skyborne.boss-bar"), 1f, BossBar.Color.YELLOW,
                BossBar.Overlay.NOTCHED_10);
        world.playSound(at, Sound.ENTITY_WITHER_SPAWN, 2f, 1.2f);
        for (Player p : playersInside()) {
            plugin.lang().send(p, "skyborne.boss");
        }
    }

    private void showBar(List<Player> here) {
        if (bar == null) {
            return;
        }
        Set<UUID> now = new HashSet<>();
        for (Player p : here) {
            now.add(p.getUniqueId());
            if (barViewers.add(p.getUniqueId())) {
                p.showBossBar(bar);
            }
        }
        barViewers.removeIf(id -> {
            if (now.contains(id)) {
                return false;
            }
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.hideBossBar(bar);
            }
            return true;
        });
    }

    private void hideBar() {
        if (bar != null) {
            for (UUID id : barViewers) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    p.hideBossBar(bar);
                }
            }
        }
        barViewers.clear();
        bar = null;
    }

    private void end(boolean won) {
        hideBar();
        removeMobs();
        state = State.ENDING;
        clearAt = Instant.now().plusSeconds(won ? 60 : 30);
        if (!won) {
            announcer.local(plugin.lang().get("skyborne.failed"));
            return;
        }
        int count = 0;
        for (UUID id : participants) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || damage.getOrDefault(id, 0.0) < settings().minDamage()) {
                continue;
            }
            count++;
            plugin.lang().send(p, "skyborne.victory-personal");
            rewards.pay(p, settings().rewards(), "skyborne", "skyborne:" + eventId + ":" + id);
            plugin.chapters().eventWon(p, ChapterGoal.Kind.SKYBORNE);
        }
        world.playSound(new Location(world, ox, oy, oz), Sound.UI_TOAST_CHALLENGE_COMPLETE, 3f, 1f);
        announcer.network(plugin.lang().get("skyborne.victory", Placeholder.unparsed("count", Integer.toString(count))));
        plugin.getSLF4JLogger().info("Skyborne fortress {} won; {} player(s) rewarded", eventId, count);
    }

    private void removeMobs() {
        for (UUID id : mobs) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) {
                e.remove();
            }
        }
        mobs.clear();
        boss = null;
    }

    /** Ends the event now: the fortress drifts away (admin stop, an error, or a Frontier rotation). */
    public void abort() {
        if (blocks != null) {
            blocks.cancel();
            blocks = null;
        }
        if (state == State.IDLE) {
            return;
        }
        if (state == State.ANNOUNCED) {
            state = State.IDLE;
            scheduleNext();
            return;
        }
        hideBar();
        removeMobs();
        clear();
    }

    private void clear() {
        for (Player p : world.getPlayers()) {
            Location at = p.getLocation();
            int dx = at.getBlockX() - ox;
            int dz = at.getBlockZ() - oz;
            if (dx >= FortressLayout.MIN_X && dx <= FortressLayout.MAX_X && dz >= FortressLayout.MIN_Z
                    && dz <= FortressLayout.MAX_Z && at.getY() > oy - 20) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 30 * 20, 0, false, false, true));
                plugin.lang().send(p, "skyborne.drifting");
            }
        }
        state = State.ENDING;
        Iterator<FortressLayout.Placement> it = layout.iterator();
        blocks = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (int i = 0; i < BLOCKS_PER_TICK && it.hasNext(); i++) {
                clearOne(it.next());
            }
            if (!it.hasNext()) {
                blocks.cancel();
                blocks = null;
                releaseChunks();
                deleteJournal();
                state = State.IDLE;
                scheduleNext();
            }
        }, 1L, 1L);
    }

    /** Removes one fortress block, if it's still what the fortress put there. */
    private void clearOne(FortressLayout.Placement p) {
        if (world == null || oy == 0) {
            return;
        }
        Block b = world.getBlockAt(ox + p.dx(), oy + p.dy(), oz + p.dz());
        if (b.getType().name().equals(p.material())) {
            b.setType(Material.AIR, false);
        }
    }

    private void releaseChunks() {
        if (world != null) {
            world.removePluginChunkTickets(plugin);
        }
    }

    // ------------------------------------------------------------------ journal

    private void writeJournal() {
        try {
            Files.createDirectories(journal.getParent());
            Files.writeString(journal, world.getKey().asString() + " " + ox + " " + oy + " " + oz,
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

    /**
     * A fortress left by a crash: removed once its world is loaded (the Frontier loads its
     * worlds a moment after startup). If the Frontier has loaded and that world isn't among
     * them, it was rotated away and archived with the fortress in it: just forget it.
     */
    private void clearLeftover() {
        try {
            String[] p = Files.readString(journal, StandardCharsets.UTF_8).trim().split(" ");
            NamespacedKey key = NamespacedKey.fromString(p[0]);
            World w = key == null ? null : Bukkit.getWorld(key);
            if (w == null && !frontier.ready()) {
                return;
            }
            if (w != null) {
                world = w;
                ox = Integer.parseInt(p[1]);
                oy = Integer.parseInt(p[2]);
                oz = Integer.parseInt(p[3]);
                for (FortressLayout.Placement pl : layout) {
                    clearOne(pl);
                }
                for (Entity e : w.getEntities()) {
                    if (e.getPersistentDataContainer().has(mobKey)) {
                        e.remove();
                    }
                }
                plugin.getSLF4JLogger().info("Removed a Skyborne fortress left at {} {} {}", ox, oy, oz);
            }
            world = null;
            oy = 0;
            Files.deleteIfExists(journal);
        } catch (IOException | RuntimeException e) {
            plugin.getSLF4JLogger().warn("Couldn't read {}: {}", journal, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ the updraft

    private void updraftTick() {
        if (world == null || (state != State.ACTIVE && state != State.BOSS && state != State.ENDING) || oy == 0) {
            return;
        }
        double cx = ox + FortressLayout.UPDRAFT_X + 0.5;
        double cz = oz + FortressLayout.UPDRAFT_Z + 0.5;
        boolean particles = Bukkit.getCurrentTick() % 10 == 0 && state != State.ENDING;
        if (particles) {
            int ground = world.getHighestBlockYAt((int) Math.floor(cx), (int) Math.floor(cz), HeightMap.MOTION_BLOCKING);
            for (int y = ground + 1; y < oy; y += 3) {
                world.spawnParticle(Particle.CLOUD, cx, y, cz, 2, 0.4, 0.6, 0.4, 0.01);
            }
            world.spawnParticle(Particle.END_ROD, cx, oy + 1, cz, 3, 0.3, 0.3, 0.3, 0.01);
        }
        if (state == State.ENDING) {
            return;
        }
        for (Player p : world.getPlayers()) {
            Location at = p.getLocation();
            double dx = at.getX() - cx;
            double dz = at.getZ() - cz;
            if (dx * dx + dz * dz > UPDRAFT_RADIUS * UPDRAFT_RADIUS || at.getY() > oy + 5) {
                continue;
            }
            p.setFallDistance(0);
            if (at.getY() < oy + 1.5) {
                p.setVelocity(new Vector(-dx * 0.1, 0.8, -dz * 0.1));
            } else {
                // At the top: onto the pad (north of the column).
                p.setVelocity(new Vector(0, 0.2, -0.7));
            }
        }
        // Falling off the island: a gentle way down.
        if (Bukkit.getCurrentTick() % 10 == 0) {
            for (Player p : world.getPlayers()) {
                Location at = p.getLocation();
                int rx = at.getBlockX() - ox;
                int rz = at.getBlockZ() - oz;
                if (rx >= FortressLayout.MIN_X - 8 && rx <= FortressLayout.MAX_X + 8 && rz >= FortressLayout.MIN_Z - 8
                        && rz <= FortressLayout.MAX_Z + 8 && at.getY() < oy - 2 && p.getFallDistance() > 6
                        && !p.hasPotionEffect(PotionEffectType.SLOW_FALLING)) {
                    p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 15 * 20, 0, false, false, true));
                }
            }
        }
    }

    // ------------------------------------------------------------------ players and commands

    /** {@code /skyborne}: where and when. */
    public void info(Player player) {
        Instant now = Instant.now();
        switch (state) {
            case IDLE -> plugin.lang().send(player, "skyborne.info-next", Placeholder.unparsed("time",
                    Durations.format(nextAt.isAfter(now) ? Duration.between(now, nextAt) : Duration.ZERO)));
            case ANNOUNCED, BUILDING -> plugin.lang().send(player, "skyborne.info-soon", where(),
                    Placeholder.unparsed("time", Durations.format(startsAt.isAfter(now) ? Duration.between(now,
                            startsAt) : Duration.ZERO)));
            case ACTIVE, BOSS -> plugin.lang().send(player, "skyborne.info-active", where(),
                    Placeholder.unparsed("time", Durations.format(Duration.between(now,
                            startedAt.plus(Duration.ofMinutes(settings().timeLimitMinutes()))).isNegative()
                            ? Duration.ZERO : Duration.between(now,
                            startedAt.plus(Duration.ofMinutes(settings().timeLimitMinutes()))))));
            case ENDING -> plugin.lang().send(player, "skyborne.info-ending");
        }
    }

    /** {@code /skyborne go}: to the foot of the updraft. */
    public void go(Player player) {
        if (state == State.IDLE || state == State.ENDING || world == null) {
            plugin.lang().send(player, "skyborne.none");
            return;
        }
        if (plugin.pvp().inCombat(player.getUniqueId())) {
            plugin.lang().send(player, "pvp.no-teleport");
            return;
        }
        long now = System.currentTimeMillis();
        Long until = goCooldown.get(player.getUniqueId());
        if (until != null && until > now) {
            plugin.lang().send(player, "frontier.travel-cooldown",
                    Placeholder.unparsed("time", Durations.format(Duration.ofMillis(until - now))));
            return;
        }
        goCooldown.put(player.getUniqueId(), now + 30_000L);
        int x = ox + FortressLayout.UPDRAFT_X;
        int z = oz + FortressLayout.UPDRAFT_Z;
        World w = world;
        w.getChunkAtAsync(x >> 4, z >> 4, true).thenAccept(chunk -> {
            int y = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING) + 1;
            if (oy != 0 && y >= oy) {
                y = oy + 1;
            }
            Location at = new Location(w, x + 0.5, y, z + 3.5, 180, -60);
            player.setFallDistance(0);
            player.teleportAsync(at).thenAccept(ok -> {
                if (ok) {
                    plugin.lang().send(player, "skyborne.went");
                }
            });
        });
    }

    /** {@code /skyborne start [minutes]}: announce a fortress now, appearing after {@code minutes}. */
    public void forceStart(int minutes, Consumer<String> report) {
        if (state != State.IDLE) {
            report.accept("A Skyborne event is already " + state.name().toLowerCase(java.util.Locale.ROOT) + ".");
            return;
        }
        Instant now = Instant.now();
        if (announce(now, now.plus(Duration.ofMinutes(Math.max(0, minutes))).plusSeconds(minutes == 0 ? 2 : 0), true)) {
            report.accept("Skyborne fortress announced over " + ox + ", " + oz + ".");
        } else {
            report.accept("No Frontier is open.");
        }
    }

    public String status() {
        return "Skyborne: " + state.name().toLowerCase(java.util.Locale.ROOT)
                + (state == State.IDLE ? ", next " + nextAt : " at " + ox + " " + oy + " " + oz + ", wave " + wave
                + ", " + participants.size() + " participant(s)");
    }

    // ------------------------------------------------------------------ events

    private static Player responsible(Entity damager) {
        if (damager instanceof Player p) {
            return p;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player p) {
            return p;
        }
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (!event.getEntity().getPersistentDataContainer().has(mobKey)) {
            return;
        }
        Player p = responsible(event.getDamager());
        if (p != null) {
            damage.merge(p.getUniqueId(), event.getFinalDamage(), Double::sum);
            participants.add(p.getUniqueId());
        }
    }

    private boolean protectedBlock(Block b) {
        return state != State.IDLE && state != State.ANNOUNCED && oy != 0 && inside(b.getLocation());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (protectedBlock(event.getBlock()) && !event.getPlayer().hasPermission(ADMIN)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (protectedBlock(event.getBlock()) && !event.getPlayer().hasPermission(ADMIN)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (protectedBlock(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(this::protectedBlock);
    }

    /** Someone who logged out on the fortress comes back high in the sky: let them down gently. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        if (!frontier.inFrontier(p.getWorld()) || inside(p.getLocation())) {
            return;
        }
        Location at = p.getLocation();
        if (at.getY() - p.getWorld().getHighestBlockYAt(at, HeightMap.MOTION_BLOCKING) > 20) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 30 * 20, 0, false, false, true));
        }
    }

    /**
     * The open Frontier changed: any event there is over. The old world is being archived, so
     * its fortress stays in the archive instead of being taken apart block by block.
     */
    public void onRotate() {
        if (blocks != null) {
            blocks.cancel();
            blocks = null;
        }
        hideBar();
        removeMobs();
        releaseChunks();
        deleteJournal();
        world = null;
        oy = 0;
        state = State.IDLE;
        scheduleNext();
    }
}
