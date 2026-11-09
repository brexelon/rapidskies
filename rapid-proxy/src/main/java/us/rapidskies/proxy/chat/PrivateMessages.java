package us.rapidskies.proxy.chat;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.proxy.Suggest;
import us.rapidskies.proxy.session.VanishedStaff;

/**
 * Cross-server private messages (PLAN.md §4): {@code /msg <player> <message>} and
 * {@code /r <message>} to answer whoever you last talked to. They run on the proxy, so
 * they reach players on any server. Muted players can't use them (LiteBans
 * {@code mutes.command_blacklist}), and Velocity logs every command for moderation.
 * Vanished staff can't be messaged by players who can't see them, though they can still
 * answer anyone who wrote to them first.
 */
public final class PrivateMessages {

    private final ProxyServer proxy;
    private final Lang lang;
    private final VanishedStaff vanish;
    /** Who each player last messaged or heard from. */
    private final Map<UUID, UUID> lastPartner = new ConcurrentHashMap<>();

    public PrivateMessages(ProxyServer proxy, Lang lang, VanishedStaff vanish) {
        this.proxy = proxy;
        this.lang = lang;
        this.vanish = vanish;
    }

    public SimpleCommand msgCommand() {
        return new SimpleCommand() {
            @Override
            public void execute(Invocation invocation) {
                if (!(invocation.source() instanceof Player sender)) {
                    lang.send(invocation.source(), "errors.players-only");
                    return;
                }
                String[] args = invocation.arguments();
                if (args.length < 2) {
                    lang.send(sender, "msg.usage");
                    return;
                }
                Optional<Player> target = proxy.getPlayer(args[0])
                        .filter(t -> !vanish.hiddenFrom(t.getUniqueId(), sender));
                if (target.isEmpty()) {
                    lang.send(sender, "errors.not-online", Placeholder.unparsed("player", args[0]));
                    return;
                }
                send(sender, target.get(), String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
            }

            @Override
            public CompletableFuture<List<String>> suggestAsync(Invocation invocation) {
                String[] args = invocation.arguments();
                if (args.length > 1) {
                    return Suggest.done(List.of());
                }
                return Suggest.done(Suggest.matching(proxy.getAllPlayers().stream()
                        .filter(p -> !vanish.hiddenFrom(p.getUniqueId(), invocation.source()))
                        .map(Player::getUsername).toList(), Suggest.last(args)));
            }
        };
    }

    public SimpleCommand replyCommand() {
        return invocation -> {
            if (!(invocation.source() instanceof Player sender)) {
                lang.send(invocation.source(), "errors.players-only");
                return;
            }
            if (invocation.arguments().length == 0) {
                lang.send(sender, "msg.reply-usage");
                return;
            }
            Optional<Player> target = Optional.ofNullable(lastPartner.get(sender.getUniqueId())).flatMap(proxy::getPlayer);
            if (target.isEmpty()) {
                lang.send(sender, "msg.nobody-to-reply");
                return;
            }
            send(sender, target.get(), String.join(" ", invocation.arguments()));
        };
    }

    private void send(Player sender, Player target, String message) {
        if (sender.equals(target)) {
            lang.send(sender, "msg.self");
            return;
        }
        var text = Placeholder.unparsed("message", message);
        lang.send(sender, "msg.to", Placeholder.unparsed("player", target.getUsername()), text);
        lang.send(target, "msg.from", Placeholder.unparsed("player", sender.getUsername()), text);
        lastPartner.put(sender.getUniqueId(), target.getUniqueId());
        lastPartner.put(target.getUniqueId(), sender.getUniqueId());
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        lastPartner.remove(event.getPlayer().getUniqueId());
    }
}
