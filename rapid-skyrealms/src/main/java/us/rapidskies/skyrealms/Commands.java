package us.rapidskies.skyrealms;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
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
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import us.rapidskies.core.world.SpawnPoint;
import us.rapidskies.skyrealms.expedition.Theme;
import us.rapidskies.skyrealms.island.Islands.IslandRef;
import us.rapidskies.skyrealms.route.Route;
import us.rapidskies.skyrealms.route.RoutesMenu;
import us.rapidskies.skyrealms.shop.ShopMenu;
import us.rapidskies.skyrealms.tier.RealmMenu;

/**
 * rapid-skyrealms' commands: {@code /realm}, {@code /shop}, {@code /sell}, {@code /expedition},
 * {@code /route}, {@code /season} and {@code /skyrealms}. Islands themselves are
 * SuperiorSkyblock2's {@code /island}.
 */
final class Commands {

    private Commands() {
    }

    private static Optional<Player> player(RapidSkyRealms plugin, CommandSourceStack stack) {
        if (stack.getSender() instanceof Player p) {
            return Optional.of(p);
        }
        plugin.lang().send(stack.getSender(), "errors.players-only");
        return Optional.empty();
    }

    private static List<String> match(String[] args, int index, Stream<String> options) {
        if (args.length > index + 1) {
            return List.of();
        }
        String prefix = args.length <= index ? "" : args[index].toLowerCase(Locale.ROOT);
        return options.filter(s -> s.startsWith(prefix)).toList();
    }

    // ------------------------------------------------------------------ /realm

    /** {@code /realm}: the tier menu; {@code ascend}; staff: {@code set <island|player> <tier>}. */
    static BasicCommand realm(RapidSkyRealms plugin) {
        return new BasicCommand() {
            @Override
            public void execute(CommandSourceStack stack, String[] args) {
                String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
                if (sub.equals("set")) {
                    adminSet(plugin, stack.getSender(), args);
                    return;
                }
                player(plugin, stack).ifPresent(p -> {
                    if (sub.equals("ascend")) {
                        plugin.tiers().ascend(p);
                    } else {
                        RealmMenu.open(plugin, p);
                    }
                });
            }

            @Override
            public Collection<String> suggest(CommandSourceStack stack, String[] args) {
                if (args.length <= 1) {
                    Stream<String> subs = stack.getSender().hasPermission("rapid.skyrealms.admin")
                            ? Stream.of("ascend", "set") : Stream.of("ascend");
                    return match(args, 0, subs);
                }
                if (args.length == 3 && args[0].equalsIgnoreCase("set")) {
                    return match(args, 2, plugin.settings().tiers().stream().map(SkyRealmsSettings.Tier::id));
                }
                return List.of();
            }

            @Override
            public String permission() {
                return "rapid.command.realm";
            }
        };
    }

    private static void adminSet(RapidSkyRealms plugin, CommandSender sender, String[] args) {
        if (!sender.hasPermission("rapid.skyrealms.admin")) {
            plugin.lang().send(sender, "errors.no-permission");
            return;
        }
        if (args.length != 3) {
            plugin.lang().send(sender, "realm.set-usage");
            return;
        }
        Optional<IslandRef> island = plugin.islands().find(args[1]);
        Optional<SkyRealmsSettings.Tier> tier = plugin.settings().tier(args[2].toLowerCase(Locale.ROOT));
        if (island.isEmpty() || tier.isEmpty()) {
            plugin.lang().send(sender, "realm.set-usage");
            return;
        }
        plugin.tiers().setTier(island.get(), tier.get(), () -> plugin.lang().send(sender, "realm.set-done",
                Placeholder.unparsed("island", island.get().name()),
                Placeholder.component("tier", plugin.tiers().name(tier.get()))));
    }

    // ------------------------------------------------------------------ /shop, /sell

    static BasicCommand shop(RapidSkyRealms plugin) {
        return new BasicCommand() {
            @Override
            public void execute(CommandSourceStack stack, String[] args) {
                player(plugin, stack).ifPresent(p -> ShopMenu.open(plugin, p));
            }

            @Override
            public String permission() {
                return "rapid.command.shop";
            }
        };
    }

    /** {@code /sell [hand|all]}: the held stack by default. */
    static BasicCommand sell(RapidSkyRealms plugin) {
        return new BasicCommand() {
            @Override
            public void execute(CommandSourceStack stack, String[] args) {
                player(plugin, stack).ifPresent(p -> {
                    String sub = args.length == 0 ? "hand" : args[0].toLowerCase(Locale.ROOT);
                    switch (sub) {
                        case "hand" -> plugin.shop().sellHand(p);
                        case "all", "inventory", "inv" -> plugin.shop().sellAll(p);
                        default -> plugin.lang().send(p, "shop.sell-usage");
                    }
                });
            }

            @Override
            public Collection<String> suggest(CommandSourceStack stack, String[] args) {
                return match(args, 0, Stream.of("hand", "all"));
            }

            @Override
            public String permission() {
                return "rapid.command.sell";
            }
        };
    }

    // ------------------------------------------------------------------ /expedition

    static BasicCommand expedition(RapidSkyRealms plugin) {
        return new BasicCommand() {
            @Override
            public void execute(CommandSourceStack stack, String[] args) {
                CommandSender sender = stack.getSender();
                String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
                var service = plugin.expeditions();
                switch (sub) {
                    case "go", "join", "tp" -> player(plugin, stack).ifPresent(service::go);
                    case "start" -> {
                        if (!sender.hasPermission("rapid.skyrealms.expedition.admin")) {
                            plugin.lang().send(sender, "errors.no-permission");
                            return;
                        }
                        Theme theme = args.length > 1 ? Theme.byId(args[1]).orElse(null) : null;
                        if (args.length > 1 && theme == null) {
                            plugin.lang().send(sender, "expedition.unknown-theme",
                                    Placeholder.unparsed("themes", String.join(", ", Theme.ids())));
                            return;
                        }
                        plugin.lang().send(sender, service.startNow(theme) ? "expedition.started" : "expedition.busy");
                    }
                    case "stop" -> {
                        if (!sender.hasPermission("rapid.skyrealms.expedition.admin")) {
                            plugin.lang().send(sender, "errors.no-permission");
                            return;
                        }
                        plugin.lang().send(sender, service.stopNow() ? "expedition.stopped" : "expedition.not-active");
                    }
                    default -> plugin.lang().send(sender, switch (service.phase()) {
                        case ACTIVE -> "expedition.status-here";
                        case WARNED, BUILDING -> "expedition.status-arriving";
                        case LEAVING -> "expedition.status-leaving";
                        default -> "expedition.status-next";
                    }, service.status());
                }
            }

            @Override
            public Collection<String> suggest(CommandSourceStack stack, String[] args) {
                boolean admin = stack.getSender().hasPermission("rapid.skyrealms.expedition.admin");
                if (args.length <= 1) {
                    return match(args, 0, admin ? Stream.of("go", "start", "stop") : Stream.of("go"));
                }
                if (admin && args[0].equalsIgnoreCase("start")) {
                    return match(args, 1, Theme.ids().stream());
                }
                return List.of();
            }

            @Override
            public String permission() {
                return "rapid.command.expedition";
            }
        };
    }

    // ------------------------------------------------------------------ /route

    static BasicCommand route(RapidSkyRealms plugin) {
        return new BasicCommand() {
            @Override
            public void execute(CommandSourceStack stack, String[] args) {
                CommandSender sender = stack.getSender();
                String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
                if (sub.equals("admin")) {
                    if (!sender.hasPermission("rapid.skyrealms.admin")) {
                        plugin.lang().send(sender, "errors.no-permission");
                    } else if (args.length == 3 && args[1].equalsIgnoreCase("end") && id(args[2]) > 0) {
                        long id = id(args[2]);
                        plugin.routes().adminEnd(id, () -> plugin.lang().send(sender, "route.admin-ended",
                                Placeholder.unparsed("id", Long.toString(id))));
                    } else {
                        plugin.lang().send(sender, "route.admin-usage");
                    }
                    return;
                }
                Optional<Player> who = player(plugin, stack);
                if (who.isEmpty()) {
                    return;
                }
                Player p = who.get();
                var routes = plugin.routes();
                switch (sub) {
                    case "" -> RoutesMenu.open(plugin, p);
                    case "depot" -> RoutesMenu.openDepot(plugin, p);
                    case "offer" -> offer(plugin, p, args);
                    case "accept", "decline", "cancel", "chest" -> {
                        long id = args.length > 1 ? id(args[1]) : -1;
                        if (id <= 0) {
                            plugin.lang().send(p, "route.usage");
                            return;
                        }
                        switch (sub) {
                            case "accept" -> routes.accept(p, id);
                            case "chest" -> routes.setChest(p, id);
                            default -> routes.cancel(p, id);
                        }
                    }
                    default -> plugin.lang().send(p, "route.usage");
                }
            }

            @Override
            public Collection<String> suggest(CommandSourceStack stack, String[] args) {
                if (args.length <= 1) {
                    return match(args, 0, Stream.of("offer", "accept", "decline", "cancel", "chest", "depot"));
                }
                if (args[0].equalsIgnoreCase("offer")) {
                    return switch (args.length) {
                        case 3 -> match(args, 2, Stream.of("sell", "buy"));
                        case 6 -> match(args, 5, plugin.routes().intervals().stream());
                        default -> List.of();
                    };
                }
                return List.of();
            }

            @Override
            public String permission() {
                return "rapid.command.route";
            }
        };
    }

    /** {@code /route offer <island> <sell|buy> <amount> <price> <interval>}, the held item being traded. */
    private static void offer(RapidSkyRealms plugin, Player p, String[] args) {
        if (args.length != 6) {
            plugin.lang().send(p, "route.offer-usage", Placeholder.unparsed("intervals",
                    String.join("|", plugin.routes().intervals())));
            return;
        }
        Route.Side side = switch (args[2].toLowerCase(Locale.ROOT)) {
            case "sell", "selling" -> Route.Side.SELLER;
            case "buy", "buying" -> Route.Side.BUYER;
            default -> null;
        };
        long qty = number(args[3]);
        long price = number(args[4]);
        if (side == null || qty <= 0 || price <= 0 || qty > Integer.MAX_VALUE) {
            plugin.lang().send(p, "route.offer-usage", Placeholder.unparsed("intervals",
                    String.join("|", plugin.routes().intervals())));
            return;
        }
        plugin.routes().offer(p, args[1], side, (int) qty, price, args[5].toLowerCase(Locale.ROOT));
    }

    private static long id(String s) {
        try {
            return Long.parseLong(s.replace("#", ""));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** {@code 2,000}, {@code 2k}, {@code 1.5k}, {@code 1m}; -1 if it isn't a whole number. */
    static long number(String s) {
        String t = s.trim().toLowerCase(Locale.ROOT).replace(",", "").replace("_", "");
        long scale = 1;
        if (t.endsWith("k")) {
            scale = 1_000;
            t = t.substring(0, t.length() - 1);
        } else if (t.endsWith("m")) {
            scale = 1_000_000;
            t = t.substring(0, t.length() - 1);
        }
        try {
            java.math.BigDecimal v = new java.math.BigDecimal(t).multiply(java.math.BigDecimal.valueOf(scale));
            return v.signum() < 0 || v.stripTrailingZeros().scale() > 0 ? -1 : v.longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------ /season

    static BasicCommand season(RapidSkyRealms plugin) {
        return new BasicCommand() {
            @Override
            public void execute(CommandSourceStack stack, String[] args) {
                CommandSender sender = stack.getSender();
                if (args.length >= 1 && args[0].equalsIgnoreCase("end")) {
                    if (!sender.hasPermission("rapid.skyrealms.season.admin")) {
                        plugin.lang().send(sender, "errors.no-permission");
                    } else if (args.length == 2 && args[1].equalsIgnoreCase("confirm")) {
                        plugin.lang().send(sender, plugin.seasons().close() ? "season.ending" : "season.cant-end");
                    } else {
                        plugin.lang().send(sender, "season.end-confirm");
                    }
                    return;
                }
                player(plugin, stack).ifPresent(p -> plugin.seasons().sendStandings(p));
            }

            @Override
            public Collection<String> suggest(CommandSourceStack stack, String[] args) {
                return stack.getSender().hasPermission("rapid.skyrealms.season.admin") ? match(args, 0, Stream.of("end"))
                        : List.of();
            }

            @Override
            public String permission() {
                return "rapid.command.season";
            }
        };
    }

    // ------------------------------------------------------------------ /skyrealms

    /**
     * {@code /skyrealms reload}: re-reads the config and lang and rebuilds the NPCs;
     * {@code /skyrealms here}: the position you stand at as an NPC location, click to copy.
     * Positions belong in the skyrealms profile in infra/ (rsdeploy would put the old ones back
     * otherwise).
     */
    static BasicCommand admin(RapidSkyRealms plugin) {
        return new BasicCommand() {
            @Override
            public void execute(CommandSourceStack stack, String[] args) {
                CommandSender sender = stack.getSender();
                String sub = args.length == 1 ? args[0].toLowerCase(Locale.ROOT) : "";
                switch (sub) {
                    case "reload" -> plugin.lang().send(sender,
                            plugin.reload() ? "admin.reloaded" : "admin.reload-failed");
                    case "here" -> here(sender);
                    default -> plugin.lang().send(sender, "admin.usage");
                }
            }

            private void here(CommandSender sender) {
                if (!(sender instanceof Player player)) {
                    plugin.lang().send(sender, "errors.players-only");
                    return;
                }
                Location at = player.getLocation();
                // Same rounding as /setspawn (2 decimals, yaw and pitch to 1).
                String point = new SpawnPoint(at.getWorld().getKey().asString(), at.getX(), at.getY(), at.getZ(),
                        at.getYaw(), at.getPitch()).format();
                plugin.lang().send(player, "admin.here", Placeholder.component("point",
                        Component.text("\"" + point + "\"").clickEvent(ClickEvent.copyToClipboard(point))
                                .hoverEvent(HoverEvent.showText(plugin.lang().get("admin.copy-hint")))));
            }

            @Override
            public Collection<String> suggest(CommandSourceStack stack, String[] args) {
                return match(args, 0, Stream.of("here", "reload"));
            }

            @Override
            public String permission() {
                return "rapid.skyrealms.admin";
            }
        };
    }
}
