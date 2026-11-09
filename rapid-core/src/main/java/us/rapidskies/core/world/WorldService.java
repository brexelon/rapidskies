package us.rapidskies.core.world;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import io.papermc.paper.math.Position;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.core.world.WorldConfig.JoinSpawn;
import us.rapidskies.core.world.WorldConfig.WorldSettings;

/**
 * Spawn worlds and the spawn point (the {@code spawn} and {@code worlds} config sections).
 *
 * <p>At startup it loads the worlds it creates itself (the Horizons spawn lives next to the
 * Heartland as {@code rapidskies:spawn}), applies each managed world's game rules, time,
 * weather and border, and makes the configured spawn the world's own spawn too. Then it puts
 * joining players at the spawn ({@code always} on the hub, {@code first-join} on the
 * gamemodes), respawns players who died without a bed or anchor there, and catches anyone
 * falling off a sky island before the void does.
 *
 * <p>The pre-built worlds themselves are shipped by {@code rsdeploy.py worlds}
 * (infra/README.md → Worlds).
 */
public final class WorldService implements Listener {

    public static final String SPAWN_PERMISSION = "rapid.command.spawn";
    public static final String SET_SPAWN_PERMISSION = "rapid.command.setspawn";

    private final JavaPlugin plugin;
    private final WorldConfig config;
    private final Lang lang;
    private final Logger log;
    /** Void heights by world, for the move check. */
    private final Map<World, Integer> voidY = new HashMap<>();
    private final Set<World> protectedWorlds = new HashSet<>();
    /** Read from the async spawn-location event, so it's swapped, never mutated. */
    private volatile Location spawn;

    public WorldService(JavaPlugin plugin, WorldConfig config, Lang lang) {
        this.plugin = plugin;
        this.config = config;
        this.lang = lang;
        this.log = plugin.getSLF4JLogger();
    }

    public WorldConfig config() {
        return config;
    }

    /** Loads and configures the worlds and resolves the spawn. Call from onEnable. */
    public void start() {
        for (WorldSettings settings : config.worlds()) {
            World world = settings.create().isPresent() ? create(settings) : find(settings.key());
            if (world == null) {
                log.error("worlds.{}: no such world. Loaded worlds: {}", settings.key(), loadedWorlds());
                continue;
            }
            apply(world, settings);
        }
        config.spawn().ifPresent(point -> {
            World world = find(point.world());
            if (world == null) {
                log.error("spawn.location: world '{}' isn't loaded, so players get vanilla's spawn. Loaded worlds: {}",
                        point.world(), loadedWorlds());
                return;
            }
            setSpawn(new Location(world, point.x(), point.y(), point.z(), point.yaw(), point.pitch()));
            log.info("Spawn is {} (on join: {})", point.format(), config.onJoin().name().toLowerCase(Locale.ROOT));
        });
    }

    /** This server's spawn: the configured one, else the main world's. */
    public Location spawn() {
        Location s = spawn;
        return s != null ? s.clone() : Bukkit.getWorlds().getFirst().getSpawnLocation();
    }

    /** Moves the spawn (until the next restart) and makes it its world's own spawn too. */
    public void setSpawn(Location location) {
        World world = location.getWorld();
        world.setSpawnLocation(location);
        spawn = location.clone();
    }

    /** Sends {@code player} to the spawn. */
    public void teleport(Player player) {
        player.setFallDistance(0);
        player.teleportAsync(spawn(), PlayerTeleportEvent.TeleportCause.PLUGIN);
    }

    // ------------------------------------------------------------------ worlds

    private World create(WorldSettings settings) {
        NamespacedKey key = NamespacedKey.fromString(settings.key());
        if (key == null) {
            log.error("worlds.{}: not a valid world key (namespace:name, lowercase)", settings.key());
            return null;
        }
        World loaded = Bukkit.getWorld(key);
        if (loaded != null) {
            return loaded;
        }
        WorldConfig.Generator generator = settings.create().orElseThrow();
        WorldCreator creator = WorldCreator.ofKey(key)
                .environment(World.Environment.NORMAL)
                .generateStructures(false);
        if (generator.flatSettings != null) {
            creator.type(WorldType.FLAT).generatorSettings(generator.flatSettings);
        }
        // A brand-new world would otherwise search for a spawn spot first.
        config.spawn().filter(p -> p.world().equals(settings.key())).ifPresent(p ->
                creator.forcedSpawnPosition(Position.fine(p.x(), p.y(), p.z()), p.yaw(), p.pitch()));
        World world = creator.createWorld();
        if (world != null) {
            log.info("Loaded world {} ({})", key, generator.name().toLowerCase(Locale.ROOT));
        }
        return world;
    }

    private void apply(World world, WorldSettings settings) {
        settings.gameRules().forEach((name, value) -> setGameRule(world, settings.key(), name, value));
        settings.time().ifPresent(world::setTime);
        if (settings.clearWeather()) {
            world.setStorm(false);
            world.setThundering(false);
        }
        settings.border().ifPresent(b -> {
            world.getWorldBorder().setCenter(b.centerX(), b.centerZ());
            world.getWorldBorder().setSize(b.size());
        });
        settings.voidY().ifPresent(y -> voidY.put(world, y));
        if (settings.protect()) {
            protectedWorlds.add(world);
        }
    }

    /** Whether only builders may change {@code world} ({@link WorldProtection}). */
    public boolean isProtected(World world) {
        return protectedWorlds.contains(world);
    }

    private void setGameRule(World world, String worldKey, String name, String value) {
        NamespacedKey key = NamespacedKey.fromString(name);
        GameRule<?> rule = key == null ? null : Registry.GAME_RULE.get(key);
        if (rule == null) {
            log.warn("worlds.{}.game-rules: there's no game rule '{}' on this version", worldKey, name);
            return;
        }
        try {
            if (rule.getType() == Boolean.class) {
                if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
                    throw new IllegalArgumentException("expected true or false");
                }
                @SuppressWarnings("unchecked") GameRule<Boolean> r = (GameRule<Boolean>) rule;
                world.setGameRule(r, Boolean.parseBoolean(value));
            } else if (rule.getType() == Integer.class) {
                @SuppressWarnings("unchecked") GameRule<Integer> r = (GameRule<Integer>) rule;
                world.setGameRule(r, Integer.parseInt(value.trim()));
            } else {
                log.warn("worlds.{}.game-rules.{}: unsupported type {}", worldKey, name, rule.getType().getSimpleName());
            }
        } catch (IllegalArgumentException e) {
            log.warn("worlds.{}.game-rules.{}: bad value '{}' ({})", worldKey, name, value, e.getMessage());
        }
    }

    /** A world by key ({@code minecraft:overworld}) or, failing that, by Bukkit name ({@code hub}). */
    static World find(String ref) {
        if (ref.indexOf(':') >= 0) {
            NamespacedKey key = NamespacedKey.fromString(ref);
            return key == null ? null : Bukkit.getWorld(key);
        }
        return Bukkit.getWorld(ref);
    }

    private static String loadedWorlds() {
        return Bukkit.getWorlds().stream().map(w -> w.getKey().asString()).collect(Collectors.joining(", "));
    }

    // ------------------------------------------------------------------ players

    @EventHandler(priority = EventPriority.LOW)
    public void onSpawnLocation(AsyncPlayerSpawnLocationEvent event) {
        Location s = spawn;
        if (s == null) {
            return;
        }
        if (config.onJoin() == JoinSpawn.ALWAYS
                || (config.onJoin() == JoinSpawn.FIRST_JOIN && event.isNewPlayer())) {
            event.setSpawnLocation(s.clone());
        }
    }

    /** Deaths without a bed or anchor respawn at the spawn. LOW, so island plugins can still override. */
    @EventHandler(priority = EventPriority.LOW)
    public void onRespawn(PlayerRespawnEvent event) {
        Location s = spawn;
        if (s == null || !config.respawnAtSpawn() || event.isBedSpawn() || event.isAnchorSpawn()
                || event.getRespawnReason() != PlayerRespawnEvent.RespawnReason.DEATH) {
            return;
        }
        event.setRespawnLocation(s.clone());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (voidY.isEmpty() || !event.hasChangedBlock()) {
            return;
        }
        Location to = event.getTo();
        Integer limit = voidY.get(to.getWorld());
        if (limit != null && to.getY() < limit) {
            Player player = event.getPlayer();
            Location target = spawn();
            if (!target.getWorld().equals(to.getWorld())) {
                // A void world that isn't the spawn world: back to its own spawn.
                target = to.getWorld().getSpawnLocation();
            }
            player.setFallDistance(0);
            player.teleportAsync(target, PlayerTeleportEvent.TeleportCause.PLUGIN);
        }
    }

    // ------------------------------------------------------------------ commands

    /** {@code /spawn}. */
    public BasicCommand spawnCommand() {
        return new BasicCommand() {
            @Override
            public void execute(io.papermc.paper.command.brigadier.CommandSourceStack stack, String[] args) {
                if (!(stack.getSender() instanceof Player player)) {
                    lang.send(stack.getSender(), "errors.players-only");
                    return;
                }
                teleport(player);
                lang.send(player, "spawn.teleported");
            }

            @Override
            public String permission() {
                return SPAWN_PERMISSION;
            }
        };
    }

    /**
     * {@code /setspawn}: moves the spawn to where you stand until the next restart, and prints
     * the line for the server's profile in infra/, which is where the spawn really lives
     * (rsdeploy would put the old value back otherwise).
     */
    public BasicCommand setSpawnCommand() {
        return new BasicCommand() {
            @Override
            public void execute(io.papermc.paper.command.brigadier.CommandSourceStack stack, String[] args) {
                if (!(stack.getSender() instanceof Player player)) {
                    lang.send(stack.getSender(), "errors.players-only");
                    return;
                }
                Location at = player.getLocation();
                SpawnPoint point = new SpawnPoint(at.getWorld().getKey().asString(), at.getX(), at.getY(), at.getZ(),
                        at.getYaw(), at.getPitch());
                // Round like the printed line, so what's live matches what goes into the profile.
                SpawnPoint rounded = SpawnPoint.parse(point.format());
                setSpawn(new Location(at.getWorld(), rounded.x(), rounded.y(), rounded.z(), rounded.yaw(),
                        rounded.pitch()));
                String value = rounded.format();
                Component line = Component.text("spawn.location: \"" + value + "\"")
                        .clickEvent(ClickEvent.copyToClipboard(value))
                        .hoverEvent(HoverEvent.showText(lang.get("spawn.copy-hint")));
                lang.send(player, "spawn.set", Placeholder.component("line", line));
            }

            @Override
            public String permission() {
                return SET_SPAWN_PERMISSION;
            }
        };
    }
}
