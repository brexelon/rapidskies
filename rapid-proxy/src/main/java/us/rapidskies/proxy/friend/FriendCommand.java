package us.rapidskies.proxy.friend;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.proxy.Suggest;

/**
 * {@code /friend} (alias {@code /f}): {@code add|accept|deny|remove|list|requests|join}.
 * {@code /friend <player>} is short for {@code /friend add <player>}.
 */
public final class FriendCommand implements SimpleCommand {

    private static final List<String> SUBCOMMANDS = List.of("add", "accept", "deny", "remove", "list", "requests", "join");

    private final ProxyServer proxy;
    private final Lang lang;
    private final FriendService friends;

    public FriendCommand(ProxyServer proxy, Lang lang, FriendService friends) {
        this.proxy = proxy;
        this.lang = lang;
        this.friends = friends;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            lang.send(invocation.source(), "errors.players-only");
            return;
        }
        String[] args = invocation.arguments();
        if (args.length == 0) {
            friends.list(player, 1);
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        String arg = args.length > 1 ? args[1] : null;
        switch (sub) {
            case "list" -> friends.list(player, arg == null ? 1 : page(arg));
            case "requests" -> friends.requests(player);
            case "help" -> lang.send(player, "friend.help");
            case "add", "accept", "deny", "remove", "join" -> {
                if (arg == null) {
                    lang.send(player, "friend.help");
                    return;
                }
                switch (sub) {
                    case "add" -> friends.add(player, arg);
                    case "accept" -> friends.accept(player, arg);
                    case "deny" -> friends.deny(player, arg);
                    case "remove" -> friends.remove(player, arg);
                    default -> friends.join(player, arg);
                }
            }
            default -> friends.add(player, args[0]);
        }
    }

    private static int page(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    @Override
    public CompletableFuture<List<String>> suggestAsync(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length <= 1) {
            List<String> options = new ArrayList<>(Suggest.matching(SUBCOMMANDS, Suggest.last(args)));
            options.addAll(Suggest.players(proxy, Suggest.last(args)));
            return Suggest.done(options);
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if ((sub.equals("join") || sub.equals("remove")) && invocation.source() instanceof Player p) {
                return Suggest.done(Suggest.matching(friends.onlineFriendNames(p), args[1]));
            }
            if (sub.equals("add") || sub.equals("accept") || sub.equals("deny")) {
                return Suggest.done(Suggest.players(proxy, args[1]));
            }
        }
        return Suggest.done(List.of());
    }
}
