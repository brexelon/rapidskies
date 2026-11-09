package us.rapidskies.proxy.session;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.command.CommandExecuteEvent.CommandResult;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.slf4j.Logger;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.common.redis.Channels;
import us.rapidskies.common.redis.RedisBus;
import us.rapidskies.proxy.chat.StaffChatCommand;

/**
 * The proxy's half of freeze (PLAN.md §9; rapid-core's staff mode does the freezing). A
 * frozen player's commands are refused here too, so {@code /play} and {@code /server}
 * can't take them away from the staff member checking them; private messages still work
 * so they can talk. If they disconnect, staff chat hears about it.
 */
public final class FrozenPlayers {

    /** Proxy commands a frozen player may still use: private messages and replies. */
    private static final Set<String> ALLOWED = Set.of(
            "msg", "tell", "w", "whisper", "m", "t", "pm", "message", "r", "reply");

    private final ProxyServer proxy;
    private final Lang lang;
    private final Set<UUID> frozen = ConcurrentHashMap.newKeySet();

    /** Loads the current set (blocking: call off the event threads) and follows changes. */
    public FrozenPlayers(ProxyServer proxy, Lang lang, RedisBus redis, Logger logger) {
        this.proxy = proxy;
        this.lang = lang;
        redis.subscribeJson(Channels.FREEZE, Channels.FreezeChanged.class, change -> {
            if (change.frozen()) {
                frozen.add(change.player());
            } else {
                frozen.remove(change.player());
            }
        });
        try {
            for (String id : redis.members(Channels.FROZEN_KEY)) {
                frozen.add(UUID.fromString(id));
            }
        } catch (RuntimeException e) {
            logger.warn("Couldn't load frozen players: {}", e.getMessage());
        }
    }

    public boolean isFrozen(UUID player) {
        return frozen.contains(player);
    }

    /** First, so no other plugin acts on a command that's about to be refused. */
    @Subscribe(priority = Short.MAX_VALUE)
    public void onCommand(CommandExecuteEvent event) {
        CommandSource source = event.getCommandSource();
        if (!(source instanceof Player player) || !frozen.contains(player.getUniqueId())) {
            return;
        }
        String label = event.getCommand().split(" ", 2)[0].toLowerCase(Locale.ROOT);
        if (!ALLOWED.contains(label)) {
            event.setResult(CommandResult.denied());
            lang.send(player, "freeze.no-commands");
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        if (!frozen.contains(player.getUniqueId())) {
            return;
        }
        var who = Placeholder.unparsed("player", player.getUsername());
        for (Player staff : proxy.getAllPlayers()) {
            if (staff.hasPermission(StaffChatCommand.PERMISSION)) {
                lang.send(staff, "freeze.logged-out", who);
            }
        }
    }
}
