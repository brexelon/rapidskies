package us.rapidskies.proxy;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/** Tab-completion helpers shared by the proxy commands. */
public final class Suggest {

    private Suggest() {
    }

    /** Online players' names starting with {@code typed}, network-wide. */
    public static List<String> players(ProxyServer proxy, String typed) {
        return matching(proxy.getAllPlayers().stream().map(Player::getUsername).toList(), typed);
    }

    public static List<String> matching(Collection<String> options, String typed) {
        String t = typed.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(t)).toList();
    }

    /** The word being typed: the last argument, or "" before the first one. */
    public static String last(String[] args) {
        return args.length == 0 ? "" : args[args.length - 1];
    }

    public static CompletableFuture<List<String>> done(List<String> options) {
        return CompletableFuture.completedFuture(options);
    }
}
