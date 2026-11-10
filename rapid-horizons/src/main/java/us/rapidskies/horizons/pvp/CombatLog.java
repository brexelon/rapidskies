package us.rapidskies.horizons.pvp;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import us.rapidskies.horizons.HorizonsSettings;
import us.rapidskies.horizons.RapidHorizons;
import us.rapidskies.horizons.pvp.CombatLogRules.Leave;

/**
 * Combat logging: leaving the server while combat-tagged ({@link PvpService}) kills you where
 * you stood. Your items and XP drop there under the usual rules (keepInventory included), the
 * kill counts for the player you were fighting, the death message says you fled, and the
 * configured console commands run. You're told why when you come back.
 *
 * <p>Server restarts never count (plugins disable before Paper kicks anyone, and
 * {@link Bukkit#isStopping()} is checked as well), nor do server-side errors; kicks only
 * with {@code punish-kicks}. Switching server through the proxy ({@code /hub}) is leaving,
 * so it counts: the warning on being tagged says so, and the spawn's server NPCs refuse.
 */
public final class CombatLog implements Listener {

    public static final String BYPASS = "rapid.horizons.combatlog.bypass";

    /** A player being killed for leaving right now; {@code attacker} may be null. */
    private record Dying(String attacker) {
    }

    private final RapidHorizons plugin;
    /** Only touched on the main thread, inside one quit event. */
    private final Map<UUID, Dying> dying = new HashMap<>();
    /** Punished players who haven't been back yet: who they fled from ("" if unknown). */
    private final Map<UUID, String> punished = new ConcurrentHashMap<>();

    public CombatLog(RapidHorizons plugin) {
        this.plugin = plugin;
    }

    // LOW: before other plugins' quit handlers save anything, so they see the death.
    @EventHandler(priority = EventPriority.LOW)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        PvpService pvp = plugin.pvp();
        HorizonsSettings.CombatLog config = plugin.settings().pvp().combatLog();
        if (player.isDead() || !CombatLogRules.punish(config.enabled(), Bukkit.isStopping(), leave(event.getReason()),
                Instant.now(), pvp.combatUntil(id), player.hasPermission(BYPASS), config.punishKicks())) {
            return;
        }
        UUID opponentId = pvp.lastOpponent(id);
        Player opponent = opponentId == null ? null : Bukkit.getPlayer(opponentId);
        String attacker = opponent != null ? opponent.getName()
                : opponentId == null ? null : Bukkit.getOfflinePlayer(opponentId).getName();
        pvp.untag(id);

        dying.put(id, new Dying(attacker));
        try {
            if (opponent != null) {
                // Kill credit: their statistics, and the XP drop needs a player killer.
                player.setKiller(opponent);
            }
            // Straight to zero: no armor, totem or god mode saves a combat logger.
            player.setHealth(0);
        } finally {
            dying.remove(id);
        }
        punished.put(id, attacker == null ? "" : attacker);
        plugin.getSLF4JLogger().info("{} left in combat{} and was killed", player.getName(),
                attacker == null ? "" : " (fighting " + attacker + ")");

        for (String template : config.commands()) {
            String command = CombatLogRules.command(template, player.getName(), id, attacker);
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            } catch (RuntimeException e) {
                plugin.getSLF4JLogger().warn("Combat-log command '{}' failed: {}", command, e.getMessage());
            }
        }
    }

    /** The death message names the fleeing player and who they ran from. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        Dying d = dying.get(event.getPlayer().getUniqueId());
        if (d == null) {
            return;
        }
        var player = Placeholder.unparsed("player", event.getPlayer().getName());
        event.deathMessage(d.attacker() == null ? plugin.lang().get("pvp.combat-log.death", player)
                : plugin.lang().get("pvp.combat-log.death-fled", player,
                Placeholder.unparsed("attacker", d.attacker())));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        String from = punished.remove(event.getPlayer().getUniqueId());
        if (from == null) {
            return;
        }
        if (from.isEmpty()) {
            plugin.lang().send(event.getPlayer(), "pvp.combat-log.punished");
        } else {
            plugin.lang().send(event.getPlayer(), "pvp.combat-log.punished-fled",
                    Placeholder.unparsed("attacker", from));
        }
    }

    /** Anything Paper adds later is treated like a server-side error: not punished. */
    private static Leave leave(PlayerQuitEvent.QuitReason reason) {
        return switch (reason) {
            case DISCONNECTED -> Leave.DISCONNECTED;
            case TIMED_OUT -> Leave.TIMED_OUT;
            case KICKED -> Leave.KICKED;
            default -> Leave.ERRONEOUS_STATE;
        };
    }
}
