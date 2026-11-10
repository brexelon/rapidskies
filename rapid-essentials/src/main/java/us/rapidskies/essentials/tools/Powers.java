package us.rapidskies.essentials.tools;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import us.rapidskies.essentials.RapidEssentials;

/**
 * Flight, god mode and speed that staff switch on with {@code /fly}, {@code /god} and
 * {@code /speed}. Each is marked in the player's persistent data (saved with the player on
 * this server), so it lasts through relogs for staff who hold the command themselves, and
 * ends at the next join for anyone who doesn't: a player a moderator let fly, or staff who
 * have lost the rank since.
 *
 * <p>God mode is like creative's invulnerability: no damage except the void and
 * {@code /kill}, no hunger. A player in god mode can't hurt other players either, so it
 * can never win a fight.
 */
public final class Powers implements Listener {

    public static final String FLY = "rapid.command.fly";
    public static final String GOD = "rapid.command.god";
    public static final String SPEED = "rapid.command.speed";

    private final RapidEssentials plugin;
    private final NamespacedKey flyKey;
    private final NamespacedKey godKey;
    private final NamespacedKey speedKey;

    public Powers(RapidEssentials plugin) {
        this.plugin = plugin;
        this.flyKey = new NamespacedKey(plugin, "fly");
        this.godKey = new NamespacedKey(plugin, "god");
        this.speedKey = new NamespacedKey(plugin, "speed");
    }

    /** Survival and adventure, where flight is something given; creative and spectator always fly. */
    public static boolean survivalLike(Player player) {
        return player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE;
    }

    // ------------------------------------------------------------------ flight

    public void setFly(Player player, boolean on) {
        mark(player, flyKey, on);
        player.setAllowFlight(on);
        if (!on) {
            player.setFlying(false);
            player.setFallDistance(0);
        }
    }

    private boolean hasFly(Player player) {
        return player.getPersistentDataContainer().has(flyKey);
    }

    /**
     * The game takes flight away on a game mode change, respawn and (sometimes) a world
     * change; give it back a tick later, once the change has settled.
     */
    private void reapplyFly(Player player) {
        if (!hasFly(player)) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && hasFly(player) && survivalLike(player)) {
                player.setAllowFlight(true);
            }
        });
    }

    // ------------------------------------------------------------------ god mode

    public boolean isGod(Player player) {
        return player.getPersistentDataContainer().has(godKey);
    }

    public void setGod(Player player, boolean on) {
        mark(player, godKey, on);
        if (on && !player.isDead()) {
            heal(player);
        }
    }

    // ------------------------------------------------------------------ speed

    /** Sets the walk or fly speed to a level from 0 to 10 (1 is normal). */
    public void setSpeed(Player player, boolean fly, float level) {
        float speed = Speeds.toGame(level, fly);
        if (fly) {
            player.setFlySpeed(speed);
        } else {
            player.setWalkSpeed(speed);
        }
        mark(player, speedKey, !Speeds.isDefault(player.getWalkSpeed(), false)
                || !Speeds.isDefault(player.getFlySpeed(), true));
    }

    // ------------------------------------------------------------------ /heal, /feed

    /** Full health and food, no fire and no harmful effects. Beneficial effects stay. */
    public static void heal(Player player) {
        AttributeInstance max = player.getAttribute(Attribute.MAX_HEALTH);
        player.setHealth(max == null ? player.getHealth() : max.getValue());
        feed(player);
        player.setFireTicks(0);
        for (PotionEffect effect : player.getActivePotionEffects()) {
            if (effect.getType().getEffectCategory() == PotionEffectType.Category.HARMFUL) {
                player.removePotionEffect(effect.getType());
            }
        }
    }

    public static void feed(Player player) {
        player.setFoodLevel(20);
        player.setSaturation(10f);
        player.setExhaustion(0f);
    }

    private static void mark(Player player, NamespacedKey key, boolean on) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        if (on) {
            pdc.set(key, PersistentDataType.BYTE, (byte) 1);
        } else {
            pdc.remove(key);
        }
    }

    // ------------------------------------------------------------------ events

    /**
     * After rapid-core's staff mode has put back a crashed session's state (LOWEST), take
     * away what the player can't give themselves.
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        if (pdc.has(godKey) && !player.hasPermission(GOD)) {
            pdc.remove(godKey);
        }
        if (pdc.has(flyKey)) {
            if (!player.hasPermission(FLY)) {
                pdc.remove(flyKey);
                if (survivalLike(player)) {
                    player.setFlying(false);
                    player.setAllowFlight(false);
                }
            } else if (survivalLike(player)) {
                player.setAllowFlight(true);
            }
        }
        if (pdc.has(speedKey) && !player.hasPermission(SPEED)) {
            pdc.remove(speedKey);
            player.setWalkSpeed(Speeds.DEFAULT_WALK);
            player.setFlySpeed(Speeds.DEFAULT_FLY);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        reapplyFly(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        reapplyFly(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        reapplyFly(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && event.getCause() != DamageCause.VOID
                && event.getCause() != DamageCause.KILL && isGod(player)) {
            event.setCancelled(true);
            player.setFireTicks(0);
        }
    }

    /** God mode is for staying alive, never for winning a fight. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = null;
        if (event.getDamager() instanceof Player p) {
            attacker = p;
        } else if (event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player p) {
            attacker = p;
        }
        if (attacker != null && !attacker.equals(victim) && isGod(attacker)) {
            event.setCancelled(true);
            attacker.sendActionBar(plugin.lang().get("god.no-pvp"));
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && event.getFoodLevel() < player.getFoodLevel()
                && isGod(player)) {
            event.setCancelled(true);
        }
    }
}
