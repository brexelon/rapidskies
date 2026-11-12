package us.rapidskies.core.chat;

import java.sql.SQLException;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import us.rapidskies.common.economy.EconomyMode;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.common.profile.ModeFirstJoins;
import us.rapidskies.core.CoreSettings;
import us.rapidskies.core.RapidCore;
import us.rapidskies.core.staff.Vanish;

/**
 * Join and quit lines in chat. Minecraft's own "joined the game" / "left the game" are
 * removed: on a network every server switch would print both, and the proxy tells the team
 * when staff come, go or switch servers instead. On gamemode servers, a player's first
 * visit to the gamemode (network-wide, {@link ModeFirstJoins}) is announced to everyone
 * there and the newcomer gets a welcome.
 */
public final class JoinMessages implements Listener {

    private final RapidCore plugin;
    private final ModeFirstJoins firstJoins;
    private final CoreSettings.JoinMessages settings;
    private final EconomyMode gamemode;
    private final Vanish vanish;
    private final Lang lang;

    /** {@code gamemode} is this server's, or null on servers without one (no welcomes there). */
    public JoinMessages(RapidCore plugin, ModeFirstJoins firstJoins, CoreSettings.JoinMessages settings,
                        EconomyMode gamemode, Vanish vanish, Lang lang) {
        this.plugin = plugin;
        this.firstJoins = firstJoins;
        this.settings = settings;
        this.gamemode = gamemode;
        this.vanish = vanish;
        this.lang = lang;
    }

    /** HIGHEST: after anything that sets its own line, so it stays gone; MONITOR plugins still see it. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        if (!settings.vanilla()) {
            event.joinMessage(null);
        }
        if (gamemode != null && settings.firstJoinWelcome()) {
            Player player = event.getPlayer();
            // The server's own player data: someone who played here before this table existed
            // isn't new, whatever the table says.
            boolean playedHere = player.hasPlayedBefore();
            UUID id = player.getUniqueId();
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> recordVisit(id, playedHere));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        if (!settings.vanilla()) {
            event.quitMessage(null);
        }
    }

    private void recordVisit(UUID id, boolean playedHere) {
        boolean first;
        try {
            first = firstJoins.record(id, gamemode);
        } catch (SQLException | RuntimeException e) {
            plugin.getSLF4JLogger().warn("Couldn't record the first {} visit of {}: {}", gamemode.id(), id,
                    e.getMessage());
            return;
        }
        if (first && !playedHere) {
            Bukkit.getScheduler().runTask(plugin, () -> welcome(id));
        }
    }

    private void welcome(UUID id) {
        Player player = Bukkit.getPlayer(id);
        if (player == null) {
            return;
        }
        TagResolver tags = TagResolver.resolver(Placeholder.unparsed("player", player.getName()),
                Placeholder.component("mode", lang.get("join.mode." + gamemode.id())));
        lang.send(player, "join.first.welcome", tags);
        if (vanish.isVanished(player)) {
            // A vanished staff member's first visit stays as quiet as the rest of their arrival.
            return;
        }
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (!other.equals(player)) {
                lang.send(other, "join.first.announce", tags);
            }
        }
    }
}
