package us.rapidskies.essentials.command;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import us.rapidskies.essentials.RapidEssentials;

/** Tab-completion helpers. Paper passes a trailing "" while a new argument is being started. */
public final class Suggest {

    private Suggest() {
    }

    /** Index of the argument being typed. */
    public static int position(String[] args) {
        return Math.max(0, args.length - 1);
    }

    /** {@code options} starting with what's typed so far, ignoring case. */
    public static List<String> matching(Collection<String> options, String[] args) {
        String typed = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(typed)).toList();
    }

    /** Names of players here that {@code viewer} can see (the console sees everyone). */
    public static List<String> players(RapidEssentials plugin, CommandSender viewer, String[] args) {
        var vanish = plugin.core().staffMode().vanish();
        return matching(Bukkit.getOnlinePlayers().stream()
                .filter(p -> !(viewer instanceof Player v) || vanish.canSee(v, p))
                .map(Player::getName)
                .toList(), args);
    }
}
