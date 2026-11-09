package us.rapidskies.proxy.party;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.proxy.Suggest;

/**
 * {@code /party} (alias {@code /p}): {@code invite|accept|deny|leave|kick|promote|disband|
 * list|chat|warp}. {@code /party <player>} is short for {@code /party invite <player>}.
 */
public final class PartyCommand implements SimpleCommand {

    private static final List<String> SUBCOMMANDS =
            List.of("invite", "accept", "deny", "leave", "kick", "promote", "disband", "list", "chat", "warp");

    private final ProxyServer proxy;
    private final Lang lang;
    private final PartyService parties;

    public PartyCommand(ProxyServer proxy, Lang lang, PartyService parties) {
        this.proxy = proxy;
        this.lang = lang;
        this.parties = parties;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            lang.send(invocation.source(), "errors.players-only");
            return;
        }
        String[] args = invocation.arguments();
        if (args.length == 0) {
            lang.send(player, "party.help");
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "invite", "add" -> withPlayer(player, args, 1, t -> parties.invite(player, t));
            case "accept", "join" -> withOptionalPlayer(player, args, 1, t -> parties.accept(player, t));
            case "deny", "decline" -> withOptionalPlayer(player, args, 1, t -> parties.deny(player, t));
            case "leave", "quit" -> parties.leave(player);
            case "kick", "remove" -> withPlayer(player, args, 1, t -> parties.kick(player, t));
            case "promote", "transfer", "leader" -> withPlayer(player, args, 1, t -> parties.transfer(player, t));
            case "disband" -> parties.disband(player);
            case "list", "info" -> parties.list(player);
            case "warp", "summon" -> parties.warp(player);
            // No short alias: LiteBans' mute list names /party chat, /p chat and /pc exactly.
            case "chat" -> {
                if (args.length < 2) {
                    lang.send(player, "party.chat-usage");
                } else {
                    parties.chat(player, String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
                }
            }
            case "help" -> lang.send(player, "party.help");
            default -> withPlayer(player, args, 0, t -> parties.invite(player, t));
        }
    }

    private void withPlayer(Player sender, String[] args, int index, java.util.function.Consumer<Player> action) {
        if (args.length <= index) {
            lang.send(sender, "party.help");
            return;
        }
        Optional<Player> target = proxy.getPlayer(args[index]);
        if (target.isEmpty()) {
            lang.send(sender, "errors.not-online", Placeholder.unparsed("player", args[index]));
            return;
        }
        action.accept(target.get());
    }

    /** Like {@link #withPlayer}, but the name may be left out (empty: "the only invite I have"). */
    private void withOptionalPlayer(Player sender, String[] args, int index,
                                    java.util.function.Consumer<Optional<Player>> action) {
        if (args.length <= index) {
            action.accept(Optional.empty());
        } else {
            withPlayer(sender, args, index, t -> action.accept(Optional.of(t)));
        }
    }

    @Override
    public CompletableFuture<List<String>> suggestAsync(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length <= 1) {
            List<String> options = new java.util.ArrayList<>(Suggest.matching(SUBCOMMANDS, Suggest.last(args)));
            options.addAll(Suggest.players(proxy, Suggest.last(args)));
            return Suggest.done(options);
        }
        if (args.length == 2 && !List.of("leave", "disband", "list", "warp", "chat").contains(args[0].toLowerCase(Locale.ROOT))) {
            return Suggest.done(Suggest.players(proxy, args[1]));
        }
        return Suggest.done(List.of());
    }
}
