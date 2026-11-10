package us.rapidskies.essentials.teleport;

import java.time.Duration;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import us.rapidskies.common.time.Durations;
import us.rapidskies.essentials.EssentialsSettings;
import us.rapidskies.essentials.RapidEssentials;

/**
 * The one way rapid-essentials' commands move players ({@code /spawn}, {@code /back},
 * {@code /home}, {@code /warp}, {@code /tpa}), so every one of them follows the same rules:
 * <ol>
 *   <li><b>Guards</b> from other plugins ({@link #addGuard}) can refuse, e.g. while
 *       combat-tagged on Horizons. Frozen players never move.</li>
 *   <li>A <b>cooldown</b> per {@link TeleportKind}, after the last successful teleport of
 *       that kind ({@value #NO_COOLDOWN} skips it).</li>
 *   <li>An optional <b>warmup</b>: the player stands still for a few seconds, and moving to
 *       another block or taking damage calls it off ({@value #NO_WARMUP} skips it).</li>
 *   <li>The destination's chunk is loaded off the main thread first, and remembered spots
 *       are moved to the nearest <b>safe footing</b> ({@link SafeSpot}).</li>
 * </ol>
 * Teleports use the {@code COMMAND} cause, so {@link BackService} remembers where they
 * started and other plugins (claims' entry denial) can still cancel them.
 *
 * <p>Main thread only, apart from the guard list.
 */
public final class Teleports implements Listener {

    public static final String NO_WARMUP = "rapid.essentials.teleport.nowarmup";
    public static final String NO_COOLDOWN = "rapid.essentials.teleport.nocooldown";

    private record Registered(Plugin owner, TeleportGuard guard) {
    }

    /** A teleport waiting out its warmup, and the block the player must stay in. */
    private record Pending(BukkitTask task, UUID world, int x, int y, int z) {
    }

    private final RapidEssentials plugin;
    private final List<Registered> guards = new CopyOnWriteArrayList<>();
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Map<UUID, Map<TeleportKind, Long>> cooldownUntil = new ConcurrentHashMap<>();

    public Teleports(RapidEssentials plugin) {
        this.plugin = plugin;
        // Built in: a frozen player is being checked by staff and stays put (Freeze also
        // cancels the teleport itself; this just says why).
        addGuard(plugin, player -> plugin.core().staffMode().freeze().isFrozen(player)
                ? plugin.lang().get("teleport.frozen") : null);
    }

    private EssentialsSettings.Teleport settings() {
        return plugin.settings().teleport();
    }

    // ------------------------------------------------------------------ guards (API)

    /** Adds a reason to refuse teleports. Removed when {@code owner} disables, or with {@link #removeGuards}. */
    public void addGuard(Plugin owner, TeleportGuard guard) {
        guards.add(new Registered(owner, guard));
    }

    public void removeGuards(Plugin owner) {
        guards.removeIf(r -> r.owner().equals(owner));
    }

    /** The first guard's refusal for {@code player}, or null if they may teleport. */
    public Component refusal(Player player) {
        for (Registered r : guards) {
            Component reason;
            try {
                reason = r.guard().check(player);
            } catch (RuntimeException e) {
                plugin.getSLF4JLogger().warn("Teleport guard from {} failed for {}", r.owner().getName(),
                        player.getName(), e);
                continue;
            }
            if (reason != null) {
                return reason;
            }
        }
        return null;
    }

    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        removeGuards(event.getPlugin());
    }

    // ------------------------------------------------------------------ teleporting

    /**
     * Sends {@code player} somewhere, following the rules above. Messages for every refusal
     * and failure are sent here; {@code arrived} runs (main thread) once they're there.
     *
     * @param destination where to, asked when the warmup ends (a {@code /tpa} target may have
     *                    moved); null if it's gone (they left, the world was unloaded)
     * @param safe        land on the nearest safe footing rather than the exact spot (remembered
     *                    spots: {@code /back}, homes), refusing if there's none
     */
    public void teleport(Player player, TeleportKind kind, Supplier<Location> destination, boolean safe,
                         Runnable arrived) {
        if (!mayStart(player, kind)) {
            return;
        }
        cancel(player, null);
        int warmup = settings().warmupSeconds();
        if (warmup <= 0 || player.hasPermission(NO_WARMUP)) {
            go(player, kind, destination, safe, arrived);
            return;
        }
        Location at = player.getLocation();
        BukkitTask task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            pending.remove(player.getUniqueId());
            if (player.isOnline()) {
                go(player, kind, destination, safe, arrived);
            }
        }, warmup * 20L);
        pending.put(player.getUniqueId(),
                new Pending(task, at.getWorld().getUID(), at.getBlockX(), at.getBlockY(), at.getBlockZ()));
        plugin.lang().send(player, "teleport.warmup", Placeholder.unparsed("seconds", Integer.toString(warmup)));
    }

    /**
     * Whether {@code player} may start a teleport of {@code kind} now (guards and cooldown),
     * telling them why not. For commands that should refuse before doing anything else
     * ({@code /tpaccept} checks the player who'd move).
     */
    public boolean mayStart(Player player, TeleportKind kind) {
        Component refused = refusal(player);
        if (refused != null) {
            player.sendMessage(refused);
            return false;
        }
        long left = cooldownLeft(player, kind);
        if (left > 0) {
            plugin.lang().send(player, "teleport.cooldown",
                    Placeholder.unparsed("time", Durations.format(Duration.ofMillis(left))));
            return false;
        }
        return true;
    }

    private void go(Player player, TeleportKind kind, Supplier<Location> destination, boolean safe, Runnable arrived) {
        // Again: they may have been tagged or frozen during the warmup.
        Component refused = refusal(player);
        if (refused != null) {
            player.sendMessage(refused);
            return;
        }
        Location target = destination.get();
        World world = target == null ? null : target.getWorld();
        if (world == null) {
            plugin.lang().send(player, "teleport.gone");
            return;
        }
        // Paper completes the chunk future on the main thread.
        world.getChunkAtAsync(target).thenAccept(chunk -> {
            if (!player.isOnline()) {
                return;
            }
            Location landing = safe ? SafeSpot.find(target) : target;
            if (landing == null) {
                plugin.lang().send(player, "teleport.unsafe");
                return;
            }
            player.setFallDistance(0);
            player.teleportAsync(landing, PlayerTeleportEvent.TeleportCause.COMMAND).thenAccept(ok -> {
                if (!ok) {
                    // Cancelled: a claim that denies them entry, a plugin that holds them.
                    plugin.lang().send(player, "teleport.failed");
                    return;
                }
                startCooldown(player, kind);
                arrived.run();
            });
        }).exceptionally(e -> {
            plugin.getSLF4JLogger().warn("Couldn't load the destination of {}'s {} teleport", player.getName(),
                    kind.id(), e);
            plugin.lang().send(player, "teleport.failed");
            return null;
        });
    }

    // ------------------------------------------------------------------ cooldowns

    private long cooldownLeft(Player player, TeleportKind kind) {
        if (player.hasPermission(NO_COOLDOWN)) {
            return 0;
        }
        Long until = cooldownUntil.getOrDefault(player.getUniqueId(), Map.of()).get(kind);
        return until == null ? 0 : Math.max(0, until - System.currentTimeMillis());
    }

    private void startCooldown(Player player, TeleportKind kind) {
        int seconds = settings().cooldown(kind);
        if (seconds <= 0) {
            return;
        }
        cooldownUntil.computeIfAbsent(player.getUniqueId(), k -> new EnumMap<>(TeleportKind.class))
                .put(kind, System.currentTimeMillis() + seconds * 1000L);
    }

    // ------------------------------------------------------------------ warmups

    /** Calls off {@code player}'s waiting teleport, telling them {@code messageKey} (null: silently). */
    private void cancel(Player player, String messageKey) {
        Pending p = pending.remove(player.getUniqueId());
        if (p == null) {
            return;
        }
        p.task().cancel();
        if (messageKey != null) {
            plugin.lang().send(player, messageKey);
        }
    }

    /** Calls off every waiting teleport (plugin disable). */
    public void cancelAll() {
        pending.values().forEach(p -> p.task().cancel());
        pending.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (pending.isEmpty() || !event.hasChangedBlock()) {
            return;
        }
        Player player = event.getPlayer();
        Pending p = pending.get(player.getUniqueId());
        Location to = event.getTo();
        if (p != null && (!to.getWorld().getUID().equals(p.world()) || to.getBlockX() != p.x()
                || to.getBlockY() != p.y() || to.getBlockZ() != p.z())) {
            cancel(player, "teleport.cancelled-move");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!pending.isEmpty() && event.getEntity() instanceof Player player) {
            cancel(player, "teleport.cancelled-damage");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cancel(event.getPlayer(), null);
    }
}
