package us.rapidskies.hub;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import us.rapidskies.core.RapidCore;

/**
 * Double jump and hub flight (PLAN.md §6.1, hub perks). Players in survival or adventure get
 * one boost per jump: the client's "start flying" (double-tapping jump) is turned into a push
 * along the way they look, and re-armed when they land. {@code rapid.hub.trail} (Rapid+)
 * adds a particle trail; {@code rapid.hub.fly} (Rapid++) can {@code /fly} instead. Both are
 * off during parkour runs, and staff mode's own flight is left alone.
 */
public final class DoubleJump implements Listener {

    public static final String FLY_PERMISSION = "rapid.hub.fly";
    public static final String TRAIL_PERMISSION = "rapid.hub.trail";

    private static final int TRAIL_TICKS = 16;
    private static final Sound JUMP_SOUND = Sound.sound(Key.key("entity.bat.takeoff"), Sound.Source.PLAYER, 0.5f, 1.4f);

    private final RapidHub hub;
    /** Players who switched hub flight on. */
    private final Set<UUID> flying = new HashSet<>();

    public DoubleJump(RapidHub hub) {
        this.hub = hub;
    }

    private static boolean survivalLike(Player player) {
        return player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE;
    }

    private static boolean inStaffMode(Player player) {
        return RapidCore.get().staffMode().isActive(player);
    }

    /**
     * Whether the client says it's standing on something. Called through {@link Entity}
     * because {@code Player#isOnGround} is deprecated for being client-controlled; that's
     * fine here: a cheater only gains extra hub jumps.
     */
    private static boolean onGround(Entity entity) {
        return entity.isOnGround();
    }

    /** Whether the player gets a boost from their next double tap. */
    private boolean canDoubleJump(Player player) {
        return hub.settings().doubleJump().enabled() && survivalLike(player) && !inStaffMode(player)
                && !flying.contains(player.getUniqueId()) && !hub.parkour().inRun(player);
    }

    /**
     * Sets flight to what the player's state allows: hub flight, an armed double jump, or
     * nothing (parkour). Call after anything that changes it.
     */
    public void refresh(Player player) {
        if (!survivalLike(player) || inStaffMode(player)) {
            return;
        }
        if (hub.parkour().inRun(player)) {
            player.setFlying(false);
            player.setAllowFlight(false);
        } else if (flying.contains(player.getUniqueId())) {
            player.setAllowFlight(true);
        } else {
            player.setFlying(false);
            player.setAllowFlight(canDoubleJump(player));
        }
    }

    /** Turns off hub flight and double jump for everyone (plugin stop). */
    public void resetAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (survivalLike(p) && !inStaffMode(p)) {
                p.setFlying(false);
                p.setAllowFlight(false);
            }
        }
        flying.clear();
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        refresh(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        flying.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(hub, () -> {
            if (player.isOnline()) {
                refresh(player);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        // Creative and spectator flight are vanilla's; apply ours once the new mode is set.
        Bukkit.getScheduler().runTask(hub, () -> {
            if (player.isOnline()) {
                refresh(player);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        Player player = event.getPlayer();
        if (!event.isFlying() || !survivalLike(player) || inStaffMode(player)
                || flying.contains(player.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        player.setFlying(false);
        player.setAllowFlight(false);
        if (canDoubleJump(player)) {
            jump(player);
        }
    }

    /** Re-arms the double jump on landing. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (player.getAllowFlight() || !event.hasChangedPosition()) {
            return;
        }
        if (onGround(player) && canDoubleJump(player)) {
            player.setAllowFlight(true);
        }
    }

    private void jump(Player player) {
        HubSettings.DoubleJump settings = hub.settings().doubleJump();
        Vector push = player.getLocation().getDirection().setY(0);
        if (push.lengthSquared() > 1e-4) {
            push.normalize().multiply(settings.forward());
        }
        player.setVelocity(push.setY(settings.up()));
        player.setFallDistance(0);
        player.playSound(JUMP_SOUND, Sound.Emitter.self());
        Location at = player.getLocation();
        at.getWorld().spawnParticle(Particle.CLOUD, at, 8, 0.25, 0.05, 0.25, 0.02);
        if (player.hasPermission(TRAIL_PERMISSION)) {
            trail(player);
        }
    }

    private void trail(Player player) {
        new BukkitRunnable() {
            private int ticks;

            @Override
            public void run() {
                if (!player.isOnline() || ticks++ >= TRAIL_TICKS || onGround(player)) {
                    cancel();
                    return;
                }
                Location at = player.getLocation().add(0, 0.2, 0);
                at.getWorld().spawnParticle(Particle.END_ROD, at, 2, 0.1, 0.05, 0.1, 0.0);
            }
        }.runTaskTimer(hub, 1, 1);
    }

    // ------------------------------------------------------------------ /fly

    /** {@code /fly}: hub flight on or off. */
    public BasicCommand flyCommand() {
        return new BasicCommand() {
            @Override
            public void execute(CommandSourceStack stack, String[] args) {
                if (!(stack.getSender() instanceof Player player)) {
                    hub.lang().send(stack.getSender(), "errors.players-only");
                    return;
                }
                if (!survivalLike(player) || inStaffMode(player)) {
                    hub.lang().send(player, "fly.not-now");
                    return;
                }
                if (hub.parkour().inRun(player)) {
                    hub.lang().send(player, "fly.parkour");
                    return;
                }
                boolean on = flying.add(player.getUniqueId());
                if (!on) {
                    flying.remove(player.getUniqueId());
                }
                refresh(player);
                if (on) {
                    player.setFlying(true);
                }
                hub.lang().send(player, on ? "fly.enabled" : "fly.disabled");
            }

            @Override
            public String permission() {
                return FLY_PERMISSION;
            }
        };
    }
}
