package us.rapidskies.essentials.tools;

import java.util.Arrays;
import java.util.List;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import us.rapidskies.core.RapidCore;
import us.rapidskies.essentials.RapidEssentials;
import us.rapidskies.essentials.command.Suggest;

/** Shared by the staff tools that act on yourself or a named player. */
final class Target {

    private Target() {
    }

    /**
     * The player named at {@code args[index]} (someone else needs {@code others}), or the
     * sender if nobody is named. Null after telling the sender why.
     */
    static Player resolve(RapidEssentials plugin, CommandSender sender, String[] args, int index, String others) {
        if (args.length > index) {
            Player named = Bukkit.getPlayerExact(args[index]);
            if (named == null || (sender instanceof Player viewer
                    && !plugin.core().staffMode().vanish().canSee(viewer, named))) {
                plugin.lang().send(sender, "errors.not-here", Placeholder.unparsed("player", args[index]));
                return null;
            }
            if (!named.equals(sender) && !sender.hasPermission(others)) {
                plugin.lang().send(sender, "errors.no-others");
                return null;
            }
            return named;
        }
        if (sender instanceof Player self) {
            return self;
        }
        plugin.lang().send(sender, "errors.console-name");
        return null;
    }

    /**
     * Whether {@code target} is in staff or build mode, telling the sender if so. Those modes
     * lend the player an inventory, game mode and flight and give the real ones back when
     * they end, so a change made meanwhile would be lost (or kept by the wrong inventory).
     */
    static boolean busy(RapidEssentials plugin, CommandSender sender, Player target) {
        RapidCore core = plugin.core();
        if (!core.staffMode().isActive(target) && !core.buildMode().isActive(target)) {
            return false;
        }
        if (target.equals(sender)) {
            plugin.lang().send(sender, "errors.busy-self");
        } else {
            plugin.lang().send(sender, "errors.busy-other", Placeholder.unparsed("player", target.getName()));
        }
        return true;
    }

    /** Player names to complete, only for senders who may name someone else. */
    static List<String> players(RapidEssentials plugin, CommandSender sender, String[] args, String others) {
        return sender.hasPermission(others) ? Suggest.players(plugin, sender, args) : List.of();
    }

    /**
     * Tells the sender what happened ({@code selfKey}, or {@code otherKey} with
     * {@code <player>}), and the target too if it was someone else ({@code targetKey}, null
     * for nothing). The target isn't told who did it, so vanished staff stay hidden.
     */
    static void report(RapidEssentials plugin, CommandSender sender, Player target, String selfKey, String otherKey,
                       String targetKey, TagResolver... extra) {
        if (target.equals(sender)) {
            plugin.lang().send(sender, selfKey, extra);
            return;
        }
        TagResolver[] withName = Arrays.copyOf(extra, extra.length + 1);
        withName[extra.length] = Placeholder.unparsed("player", target.getName());
        plugin.lang().send(sender, otherKey, withName);
        if (targetKey != null) {
            plugin.lang().send(target, targetKey, extra);
        }
    }

    /**
     * Records the use in {@code staff_actions} (PLAN.md §9), with no target when staff used
     * it on themselves. The console's own log already has its commands.
     */
    static void log(RapidEssentials plugin, CommandSender sender, String action, Player target, String detail) {
        if (sender instanceof Player staff) {
            plugin.staffLog().log(staff, action, staff.equals(target) ? null : target, detail);
        }
    }
}
