package us.rapidskies.core.cosmetic;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Egg;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import us.rapidskies.core.staff.StaffMode;
import us.rapidskies.core.world.BuildMode;

/**
 * Walking trails and projectile trails (PLAN.md §6.1, §6.2). Purely visual: a few
 * particles at the feet of a player who moved since the last pass, shown only to players
 * who can see them (so vanish and the hub's visibility toggle hide trails too); never in
 * staff or build mode, spectator, or while invisible. Projectile trails follow arrows,
 * tridents, snowballs and eggs for a few seconds or until they land.
 */
public final class Trails implements Listener {

    private static final long PERIOD_TICKS = 3;
    private static final double VIEW_DISTANCE_SQUARED = 48 * 48;
    private static final double MIN_MOVE_SQUARED = 0.04;
    private static final int PROJECTILE_TICKS = 60;

    private final Plugin plugin;
    private final CosmeticService cosmetics;
    private final StaffMode staffMode;
    private final BuildMode buildMode;
    private final boolean walking;
    private final Map<UUID, Location> last = new ConcurrentHashMap<>();
    private BukkitTask task;

    public Trails(Plugin plugin, CosmeticService cosmetics, StaffMode staffMode, BuildMode buildMode,
                  boolean walking) {
        this.plugin = plugin;
        this.cosmetics = cosmetics;
        this.staffMode = staffMode;
        this.buildMode = buildMode;
        this.walking = walking;
    }

    public void start() {
        if (walking) {
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, PERIOD_TICKS, PERIOD_TICKS);
        }
    }

    public void stop() {
        if (task != null) {
            task.cancel();
        }
        last.clear();
    }

    private boolean shows(Player player) {
        return player.getGameMode() != GameMode.SPECTATOR
                && !player.hasPotionEffect(PotionEffectType.INVISIBILITY)
                && !staffMode.isActive(player)
                && !buildMode.isActive(player)
                && !staffMode.vanish().isVanished(player);
    }

    private void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Optional<Cosmetic> trail = cosmetics.worn(player, CosmeticType.TRAIL);
            Location at = player.getLocation();
            Location before = last.put(player.getUniqueId(), at);
            if (trail.isEmpty() || before == null || before.getWorld() != at.getWorld() || !shows(player)) {
                continue;
            }
            double dx = at.getX() - before.getX();
            double dz = at.getZ() - before.getZ();
            if (dx * dx + dz * dz < MIN_MOVE_SQUARED) {
                continue;
            }
            Location feet = at.clone().add(0, 0.1, 0);
            Cosmetic.Effect effect = trail.get().particle();
            for (Player viewer : at.getWorld().getPlayers()) {
                if (viewer.getLocation().distanceSquared(at) <= VIEW_DISTANCE_SQUARED
                        && (viewer.equals(player) || viewer.canSee(player))) {
                    effect.show(viewer, feet, 2, 0.15, 0.0);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent event) {
        Projectile projectile = event.getEntity();
        if (!(projectile instanceof AbstractArrow || projectile instanceof Snowball || projectile instanceof Egg)
                || !(projectile.getShooter() instanceof Player shooter) || !shows(shooter)) {
            return;
        }
        Optional<Cosmetic> arrow = cosmetics.worn(shooter, CosmeticType.ARROW);
        if (arrow.isEmpty()) {
            return;
        }
        Cosmetic.Effect effect = arrow.get().particle();
        int[] ticks = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, t -> {
            ticks[0] += 2;
            boolean landed = projectile instanceof AbstractArrow a && a.isInBlock();
            if (!projectile.isValid() || landed || ticks[0] > PROJECTILE_TICKS) {
                t.cancel();
                return;
            }
            effect.show(projectile.getLocation(), 2, 0.05, 0.0);
        }, 1, 2);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        last.remove(event.getPlayer().getUniqueId());
    }
}
