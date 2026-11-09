package us.rapidskies.core.combat;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import us.rapidskies.core.CoreSettings;
import us.rapidskies.core.RapidCore;

/**
 * 1.8-style combat timing for every client (PLAN.md §3): no attack cooldown and no sweeping
 * hits, because 1.8 clients have neither and would be weaker in PvE and PvP otherwise.
 *
 * <p>The cooldown is removed with a named attack-speed modifier rather than by changing the
 * base value, so it's removed cleanly when the plugin is disabled or the setting is
 * turned off. Mode-specific rules (shields in PvP, duels) belong to the mode plugins.
 */
public final class CombatTiming implements Listener {

    /** Large enough that the cooldown is always full, whatever the held item's own modifier. */
    private static final double ATTACK_SPEED_BONUS = 1000;

    private final CoreSettings settings;
    private final NamespacedKey key;

    public CombatTiming(RapidCore plugin, CoreSettings settings) {
        this.settings = settings;
        this.key = new NamespacedKey(plugin, "no_attack_cooldown");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        apply(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSweep(EntityDamageByEntityEvent event) {
        if (settings.disableSweepAttacks() && event.getCause() == DamageCause.ENTITY_SWEEP_ATTACK) {
            event.setCancelled(true);
        }
    }

    public void applyToOnline() {
        Bukkit.getOnlinePlayers().forEach(this::apply);
    }

    public void removeFromOnline() {
        Bukkit.getOnlinePlayers().forEach(this::remove);
    }

    private void apply(Player player) {
        AttributeInstance speed = player.getAttribute(Attribute.ATTACK_SPEED);
        if (speed == null) {
            return;
        }
        speed.removeModifier(key);
        if (settings.noAttackCooldown()) {
            // Transient: never saved to player data, so nothing lingers if rapid-core is removed.
            speed.addTransientModifier(new AttributeModifier(key, ATTACK_SPEED_BONUS,
                    AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.ANY));
        }
    }

    private void remove(Player player) {
        AttributeInstance speed = player.getAttribute(Attribute.ATTACK_SPEED);
        if (speed != null) {
            speed.removeModifier(key);
        }
    }
}
