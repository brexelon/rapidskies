package us.rapidskies.horizons;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import us.rapidskies.core.world.SpawnPoint;
import us.rapidskies.horizons.trade.Trade;
import us.rapidskies.horizons.trade.TradesMenu;

/** rapid-horizons' commands: {@code /rtp}, {@code /pvp}, {@code /trades} and {@code /horizons}. */
final class Commands {

    private Commands() {
    }

    /** {@code /rtp} (aliases {@code /wild}, {@code /heartland}): a random safe spot in the Heartland. */
    static BasicCommand rtp(RapidHorizons plugin) {
        return new BasicCommand() {
            @Override
            public void execute(CommandSourceStack stack, String[] args) {
                if (stack.getSender() instanceof Player player) {
                    plugin.rtp().start(player, false);
                } else {
                    plugin.lang().send(stack.getSender(), "errors.players-only");
                }
            }

            @Override
            public String permission() {
                return "rapid.command.rtp";
            }
        };
    }

    /** {@code /pvp [on|off]}: no argument switches it. */
    static BasicCommand pvp(RapidHorizons plugin) {
        return new BasicCommand() {
            @Override
            public void execute(CommandSourceStack stack, String[] args) {
                if (!(stack.getSender() instanceof Player player)) {
                    plugin.lang().send(stack.getSender(), "errors.players-only");
                    return;
                }
                boolean current = plugin.pvp().enabled(player.getUniqueId());
                String arg = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
                switch (arg) {
                    case "" -> plugin.pvp().request(player, !current);
                    case "on", "enable" -> plugin.pvp().request(player, true);
                    case "off", "disable" -> plugin.pvp().request(player, false);
                    case "status" -> plugin.lang().send(player, current ? "pvp.status-on" : "pvp.status-off");
                    default -> plugin.lang().send(player, "pvp.usage");
                }
            }

            @Override
            public Collection<String> suggest(CommandSourceStack stack, String[] args) {
                String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
                return args.length <= 1 ? Stream.of("on", "off", "status").filter(s -> s.startsWith(prefix)).toList()
                        : List.of();
            }

            @Override
            public String permission() {
                return "rapid.command.pvp";
            }
        };
    }

    /** {@code /trades} opens the menu; {@code /trades pick|drop <trade>} for the keyboard. */
    static BasicCommand trades(RapidHorizons plugin) {
        return new BasicCommand() {
            @Override
            public void execute(CommandSourceStack stack, String[] args) {
                if (!(stack.getSender() instanceof Player player)) {
                    plugin.lang().send(stack.getSender(), "errors.players-only");
                    return;
                }
                if (args.length == 0) {
                    new TradesMenu(plugin).open(player);
                    return;
                }
                String sub = args[0].toLowerCase(Locale.ROOT);
                Optional<Trade> trade = args.length > 1 ? Trade.parse(args[1]) : Optional.empty();
                if ((!sub.equals("pick") && !sub.equals("drop")) || trade.isEmpty()) {
                    plugin.lang().send(player, "trades.usage");
                    return;
                }
                if (sub.equals("pick")) {
                    plugin.trades().pick(player, trade.get());
                } else {
                    plugin.trades().drop(player, trade.get());
                }
            }

            @Override
            public Collection<String> suggest(CommandSourceStack stack, String[] args) {
                if (args.length <= 1) {
                    String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
                    return Stream.of("pick", "drop").filter(s -> s.startsWith(prefix)).toList();
                }
                if (args.length == 2) {
                    String prefix = args[1].toLowerCase(Locale.ROOT);
                    return Arrays.stream(Trade.values()).map(Trade::id).filter(s -> s.startsWith(prefix)).toList();
                }
                return List.of();
            }

            @Override
            public String permission() {
                return "rapid.command.trades";
            }
        };
    }

    /**
     * {@code /horizons here}: the position you stand at as an NPC location and as a block (for
     * a corner of the portal region), each clickable to copy; {@code /horizons reload}: re-reads
     * the config and lang and rebuilds the NPCs and the portal arch. Positions belong in the
     * smp profile in infra/ (rsdeploy would put the old ones back otherwise).
     */
    static BasicCommand admin(RapidHorizons plugin) {
        return new BasicCommand() {
            @Override
            public void execute(CommandSourceStack stack, String[] args) {
                String sub = args.length == 1 ? args[0].toLowerCase(Locale.ROOT) : "";
                switch (sub) {
                    case "reload" -> plugin.lang().send(stack.getSender(),
                            plugin.reload() ? "admin.reloaded" : "admin.reload-failed");
                    case "here" -> here(stack);
                    default -> plugin.lang().send(stack.getSender(), "admin.usage");
                }
            }

            private void here(CommandSourceStack stack) {
                if (!(stack.getSender() instanceof Player player)) {
                    plugin.lang().send(stack.getSender(), "errors.players-only");
                    return;
                }
                Location at = player.getLocation();
                String world = at.getWorld().getKey().asString();
                // Same rounding as /setspawn (2 decimals, yaw and pitch to 1).
                String point = new SpawnPoint(world, at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch())
                        .format();
                String block = world + ", " + at.getBlockX() + ", " + at.getBlockY() + ", " + at.getBlockZ();
                plugin.lang().send(player, "admin.here", Placeholder.component("point", copyable(point)),
                        Placeholder.component("block", copyable(block)));
            }

            private Component copyable(String value) {
                return Component.text("\"" + value + "\"")
                        .clickEvent(ClickEvent.copyToClipboard(value))
                        .hoverEvent(HoverEvent.showText(plugin.lang().get("admin.copy-hint")));
            }

            @Override
            public Collection<String> suggest(CommandSourceStack stack, String[] args) {
                String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
                return args.length <= 1 ? Stream.of("here", "reload").filter(s -> s.startsWith(prefix)).toList()
                        : List.of();
            }

            @Override
            public String permission() {
                return "rapid.horizons.admin";
            }
        };
    }
}
