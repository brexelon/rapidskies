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

    private static java.util.function.Consumer<String> reporter(CommandSourceStack stack, RapidHorizons plugin) {
        return line -> plugin.lang().send(stack.getSender(), "admin.result",
                Placeholder.unparsed("text", line));
    }

    private static List<String> matching(String prefix, String... options) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return Stream.of(options).filter(s -> s.startsWith(p)).toList();
    }

    /**
     * {@code /frontier} (alias {@code /fr}): what's open and a travel button; {@code /frontier go}
     * travels there. Admins ({@value us.rapidskies.horizons.frontier.FrontierService#ADMIN}):
     * {@code status}, {@code prepare [modifier]}, {@code rotate}, {@code extend <days>},
     * {@code openend}.
     */
    static BasicCommand frontier(RapidHorizons plugin) {
        return new BasicCommand() {
            @Override
            public void execute(CommandSourceStack stack, String[] args) {
                var frontier = plugin.frontier();
                var sender = stack.getSender();
                String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
                boolean admin = sender.hasPermission(us.rapidskies.horizons.frontier.FrontierService.ADMIN);
                switch (sub) {
                    case "" , "info" -> info(stack);
                    case "go", "travel" -> {
                        if (sender instanceof Player player) {
                            frontier.travel(player);
                        } else {
                            plugin.lang().send(sender, "errors.players-only");
                        }
                    }
                    case "status" -> {
                        if (!admin) {
                            plugin.lang().send(sender, "frontier.usage");
                            return;
                        }
                        frontier.status().forEach(reporter(stack, plugin));
                        reporter(stack, plugin).accept(plugin.skyborne().status());
                        long held = plugin.trades().heldCrowns();
                        if (held > 0) {
                            reporter(stack, plugin).accept("Trades: " + held + " Crowns waiting for the database");
                        }
                    }
                    case "prepare" -> {
                        if (!admin) {
                            plugin.lang().send(sender, "frontier.usage");
                            return;
                        }
                        Optional<us.rapidskies.horizons.frontier.Modifier> forced = args.length > 1
                                ? us.rapidskies.horizons.frontier.Modifier.parse(args[1]) : Optional.empty();
                        if (args.length > 1 && forced.isEmpty()) {
                            reporter(stack, plugin).accept("Unknown modifier " + args[1]);
                            return;
                        }
                        frontier.prepare(forced, reporter(stack, plugin));
                    }
                    case "rotate" -> {
                        if (!admin) {
                            plugin.lang().send(sender, "frontier.usage");
                            return;
                        }
                        if (args.length < 2 || !args[1].equalsIgnoreCase("confirm")) {
                            reporter(stack, plugin).accept("This closes the open Frontier now and sends everyone in it "
                                    + "to the spawn. Type /frontier rotate confirm");
                            return;
                        }
                        frontier.rotate(reporter(stack, plugin));
                    }
                    case "extend" -> {
                        if (!admin) {
                            plugin.lang().send(sender, "frontier.usage");
                            return;
                        }
                        try {
                            frontier.extend(Integer.parseInt(args.length > 1 ? args[1] : ""), reporter(stack, plugin));
                        } catch (NumberFormatException e) {
                            reporter(stack, plugin).accept("Usage: /frontier extend <days> (negative: sooner)");
                        }
                    }
                    case "openend" -> {
                        if (!admin) {
                            plugin.lang().send(sender, "frontier.usage");
                            return;
                        }
                        frontier.openEndNow(reporter(stack, plugin));
                    }
                    default -> plugin.lang().send(sender, "frontier.usage");
                }
            }

            private void info(CommandSourceStack stack) {
                var frontier = plugin.frontier();
                var current = frontier.current();
                if (current.isEmpty()) {
                    plugin.lang().send(stack.getSender(), "frontier.closed");
                    return;
                }
                var row = current.get();
                java.time.Instant now = java.time.Instant.now();
                Component chapter = row.chapter().flatMap(id -> plugin.settings().chapter(id))
                        .map(c -> plugin.lang().get("chapter." + c.id() + ".name"))
                        .orElse(plugin.lang().get("frontier.no-chapter"));
                Component end = !plugin.settings().frontier().end().enabled() ? plugin.lang().get("frontier.end-off")
                        : frontier.endOpen() ? plugin.lang().get("frontier.end-open")
                        : plugin.lang().get("frontier.end-opens", Placeholder.unparsed("time",
                        us.rapidskies.common.time.Durations.format(
                                us.rapidskies.horizons.frontier.FrontierSchedule.until(now,
                                        row.endOpensAt().orElse(now)))));
                plugin.lang().send(stack.getSender(), "frontier.info",
                        Placeholder.component("frontier", frontier.title(row)),
                        Placeholder.component("about", plugin.lang().get("frontier.modifier." + row.modifier().id()
                                + ".about")),
                        Placeholder.unparsed("time", us.rapidskies.common.time.Durations.format(
                                us.rapidskies.horizons.frontier.FrontierSchedule.until(now, row.closesAt().orElse(now)))),
                        Placeholder.unparsed("wildlands", Integer.toString(frontier.wildlands().innerRadius())),
                        Placeholder.component("chapter", chapter), Placeholder.component("end", end));
            }

            @Override
            public Collection<String> suggest(CommandSourceStack stack, String[] args) {
                boolean admin = stack.getSender().hasPermission(us.rapidskies.horizons.frontier.FrontierService.ADMIN);
                if (args.length <= 1) {
                    String prefix = args.length == 0 ? "" : args[0];
                    return admin ? matching(prefix, "go", "info", "status", "prepare", "rotate", "extend", "openend")
                            : matching(prefix, "go", "info");
                }
                if (admin && args.length == 2 && args[0].equalsIgnoreCase("prepare")) {
                    return Arrays.stream(us.rapidskies.horizons.frontier.Modifier.values())
                            .map(us.rapidskies.horizons.frontier.Modifier::id)
                            .filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
                }
                return List.of();
            }

            @Override
            public String permission() {
                return "rapid.command.frontier";
            }
        };
    }

    /** {@code /skyborne}: when and where; {@code go}; admins: {@code start [minutes]}, {@code stop}. */
    static BasicCommand skyborne(RapidHorizons plugin) {
        return new BasicCommand() {
            @Override
            public void execute(CommandSourceStack stack, String[] args) {
                var sender = stack.getSender();
                var skyborne = plugin.skyborne();
                String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
                boolean admin = sender.hasPermission(us.rapidskies.horizons.skyborne.SkyborneService.ADMIN);
                switch (sub) {
                    case "", "info", "go" -> {
                        if (!(sender instanceof Player player)) {
                            reporter(stack, plugin).accept(skyborne.status());
                            return;
                        }
                        if (sub.equals("go")) {
                            skyborne.go(player);
                        } else {
                            skyborne.info(player);
                        }
                    }
                    case "start" -> {
                        if (!admin) {
                            plugin.lang().send(sender, "skyborne.usage");
                            return;
                        }
                        int minutes = 1;
                        if (args.length > 1) {
                            try {
                                minutes = Math.max(0, Integer.parseInt(args[1]));
                            } catch (NumberFormatException e) {
                                reporter(stack, plugin).accept("Usage: /skyborne start [minutes]");
                                return;
                            }
                        }
                        skyborne.forceStart(minutes, reporter(stack, plugin));
                    }
                    case "stop" -> {
                        if (!admin) {
                            plugin.lang().send(sender, "skyborne.usage");
                            return;
                        }
                        skyborne.abort();
                        reporter(stack, plugin).accept("The Skyborne event was stopped.");
                    }
                    default -> plugin.lang().send(sender, "skyborne.usage");
                }
            }

            @Override
            public Collection<String> suggest(CommandSourceStack stack, String[] args) {
                if (args.length > 1) {
                    return List.of();
                }
                String prefix = args.length == 0 ? "" : args[0];
                return stack.getSender().hasPermission(us.rapidskies.horizons.skyborne.SkyborneService.ADMIN)
                        ? matching(prefix, "go", "info", "start", "stop") : matching(prefix, "go", "info");
            }

            @Override
            public String permission() {
                return "rapid.command.skyborne";
            }
        };
    }

    /** {@code /chapter} (aliases {@code /chapters}, {@code /log}): the current Chapter's collection log. */
    static BasicCommand chapter(RapidHorizons plugin) {
        return new BasicCommand() {
            @Override
            public void execute(CommandSourceStack stack, String[] args) {
                if (!(stack.getSender() instanceof Player player)) {
                    plugin.lang().send(stack.getSender(), "errors.players-only");
                    return;
                }
                var chapter = plugin.chapters().current();
                if (chapter.isEmpty()) {
                    plugin.lang().send(player, "chapter.none");
                    return;
                }
                if (!plugin.chapters().loaded(player.getUniqueId())) {
                    plugin.lang().send(player, "chapter.not-loaded");
                    return;
                }
                new us.rapidskies.horizons.chapter.ChapterMenu(plugin, chapter.get()).open(player);
            }

            @Override
            public String permission() {
                return "rapid.command.chapter";
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
