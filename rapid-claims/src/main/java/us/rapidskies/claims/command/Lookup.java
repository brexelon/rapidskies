package us.rapidskies.claims.command;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.stream.Stream;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import us.rapidskies.claims.Names;
import us.rapidskies.claims.RapidClaims;
import us.rapidskies.claims.model.Access;

/** Shared bits of rapid-claims' commands: player lookups by name and tab completion. */
final class Lookup {

    private Lookup() {
    }

    /**
     * Finds a player by name (anyone who has joined the network), then runs {@code then} on
     * the main thread. {@code *} means everyone when {@code allowEveryone}.
     */
    static void player(RapidClaims plugin, CommandSender sender, String name, boolean allowEveryone,
                       Consumer<Names.Known> then) {
        if (allowEveryone && name.equals("*")) {
            then.accept(new Names.Known(Access.EVERYONE, "*"));
            return;
        }
        plugin.async(() -> plugin.names().find(name), found -> {
            if (found.isEmpty()) {
                plugin.lang().send(sender, "errors.unknown-player", Placeholder.unparsed("player", name));
            } else {
                then.accept(found.get());
            }
        }, sender);
    }

    static Collection<String> matching(String prefix, Stream<String> options) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return options.filter(o -> o.toLowerCase(Locale.ROOT).startsWith(p)).sorted().toList();
    }

    static Collection<String> onlineNames(String prefix) {
        return matching(prefix, Bukkit.getOnlinePlayers().stream().map(Player::getName));
    }

    static String arg(String[] args, int i) {
        return i < args.length ? args[i] : "";
    }

    static List<String> rest(String[] args, int from) {
        return from >= args.length ? List.of() : List.of(args).subList(from, args.length);
    }
}
