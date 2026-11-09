package us.rapidskies.core.staff;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import us.rapidskies.common.lang.Lang;

/**
 * Staff commands (PLAN.md §9): {@code /staff}, {@code /vanish}, {@code /freeze <player>},
 * {@code /invsee <player>} and {@code /endersee <player>}. All work on players on this
 * server; the proxy's {@code /server} gets staff to the right one.
 */
public final class StaffCommands {

    private final StaffMode mode;
    private final Lang lang;

    public StaffCommands(StaffMode mode, Lang lang) {
        this.mode = mode;
        this.lang = lang;
    }

    public BasicCommand staff() {
        return new PlayerCommand(StaffMode.USE, false) {
            @Override
            void run(Player player, Player target) {
                mode.toggle(player);
            }
        };
    }

    public BasicCommand vanish() {
        return new PlayerCommand(Vanish.USE, false) {
            @Override
            void run(Player player, Player target) {
                mode.toggleVanish(player);
            }
        };
    }

    public BasicCommand freeze() {
        return new PlayerCommand(Freeze.USE, true) {
            @Override
            void run(Player player, Player target) {
                mode.freeze().toggle(player, target);
            }
        };
    }

    public BasicCommand invsee() {
        return new PlayerCommand(StaffMode.INSPECT, true) {
            @Override
            void run(Player player, Player target) {
                mode.inspect(player, target, false);
            }
        };
    }

    public BasicCommand endersee() {
        return new PlayerCommand(StaffMode.INSPECT, true) {
            @Override
            void run(Player player, Player target) {
                mode.inspect(player, target, true);
            }
        };
    }

    /** A players-only command, optionally taking an online player on this server. */
    private abstract class PlayerCommand implements BasicCommand {

        private final String permission;
        private final boolean needsTarget;

        PlayerCommand(String permission, boolean needsTarget) {
            this.permission = permission;
            this.needsTarget = needsTarget;
        }

        abstract void run(Player player, Player target);

        @Override
        public String permission() {
            return permission;
        }

        @Override
        public void execute(CommandSourceStack stack, String[] args) {
            if (!(stack.getSender() instanceof Player player)) {
                lang.send(stack.getSender(), "errors.players-only");
                return;
            }
            if (!needsTarget) {
                run(player, null);
                return;
            }
            if (args.length < 1) {
                lang.send(player, "staff.usage-player");
                return;
            }
            Player target = Bukkit.getPlayerExact(args[0]);
            if (target == null || !mode.vanish().canSee(player, target)) {
                lang.send(player, "staff.not-here", Placeholder.unparsed("player", args[0]));
                return;
            }
            run(player, target);
        }

        @Override
        public Collection<String> suggest(CommandSourceStack stack, String[] args) {
            if (!needsTarget || args.length > 1 || !(stack.getSender() instanceof Player viewer)) {
                return List.of();
            }
            String typed = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream()
                    .filter(p -> mode.vanish().canSee(viewer, p))
                    .map(Player::getName)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(typed))
                    .toList();
        }
    }
}
