package us.rapidskies.market.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import us.rapidskies.common.profile.PlayerProfile;
import us.rapidskies.common.profile.ProfileRepository;
import us.rapidskies.core.RapidCore;
import us.rapidskies.market.RapidMarket;
import us.rapidskies.market.gui.BrowseMenu;
import us.rapidskies.market.gui.CollectionMenu;
import us.rapidskies.market.gui.MyListingsMenu;
import us.rapidskies.market.model.Listing;
import us.rapidskies.market.model.Prices;

/**
 * {@code /ah} (aliases {@code /auction}, {@code /auctionhouse}, {@code /market}; PLAN.md
 * §4.2). Without arguments it opens the auction house. Subcommands: {@code sell <price>
 * [hours]}, {@code search <text>}, {@code listings}, {@code collect}, {@code help}, and for
 * staff {@code admin [player <name> | history <name> [page] | remove <id>]}.
 */
public final class AuctionCommand implements BasicCommand {

    private static final List<String> SUBS = List.of("sell", "search", "listings", "collect", "help");
    private static final List<String> ADMIN_SUBS = List.of("player", "history", "remove");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);
    private static final int HISTORY_PAGE = 10;

    private final RapidMarket plugin;

    public AuctionCommand(RapidMarket plugin) {
        this.plugin = plugin;
    }

    @Override
    public String permission() {
        return "rapid.command.ah";
    }

    @Override
    public void execute(CommandSourceStack stack, String[] args) {
        CommandSender sender = stack.getSender();
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("admin")) {
            admin(sender, Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (!(sender instanceof Player p)) {
            plugin.lang().send(sender, "errors.players-only");
            return;
        }
        switch (sub) {
            case "", "browse", "open" -> new BrowseMenu(plugin, false, null, null).show(p);
            case "sell", "list" -> {
                if (args.length < 2) {
                    plugin.lang().send(p, "sell.usage", Placeholder.unparsed("hours", hours()));
                } else {
                    plugin.market().prepareSell(p, args[1], args.length >= 3 ? args[2] : null);
                }
            }
            case "search", "find" -> {
                if (args.length < 2) {
                    plugin.lang().send(p, "search.usage");
                } else {
                    new BrowseMenu(plugin, false, null, null)
                            .search(String.join(" ", Arrays.copyOfRange(args, 1, args.length))).show(p);
                }
            }
            case "listings", "mine", "my", "selling" -> new MyListingsMenu(plugin, null).show(p);
            case "collect", "collection", "bin", "claim" -> {
                if (args.length >= 2 && args[1].equalsIgnoreCase("all")) {
                    plugin.market().collectAll(p, null);
                } else {
                    new CollectionMenu(plugin, null).show(p);
                }
            }
            default -> plugin.lang().send(p, "help", Placeholder.unparsed("hours", hours()));
        }
    }

    private void admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("rapid.market.admin")) {
            plugin.lang().send(sender, "errors.no-permission");
            return;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        String a1 = args.length >= 2 ? args[1] : "";
        switch (sub) {
            case "" -> {
                if (sender instanceof Player p) {
                    new BrowseMenu(plugin, true, null, null).show(p);
                } else {
                    plugin.lang().send(sender, "admin.help");
                }
            }
            case "player", "listings" -> {
                if (!(sender instanceof Player p)) {
                    plugin.lang().send(sender, "errors.players-only");
                } else if (a1.isEmpty()) {
                    plugin.lang().send(p, "admin.help");
                } else {
                    lookup(p, a1, profile -> new BrowseMenu(plugin, true, profile.uuid(), profile.name()).show(p));
                }
            }
            case "history" -> {
                if (a1.isEmpty()) {
                    plugin.lang().send(sender, "admin.help");
                    return;
                }
                int page = 1;
                if (args.length >= 3) {
                    try {
                        page = Math.max(1, Integer.parseInt(args[2]));
                    } catch (NumberFormatException e) {
                        page = 1;
                    }
                }
                int shownPage = page;
                lookup(sender, a1, profile -> history(sender, profile, shownPage));
            }
            case "remove", "return", "takedown" -> {
                if (!(sender instanceof Player p)) {
                    plugin.lang().send(sender, "errors.players-only");
                    return;
                }
                try {
                    plugin.market().remove(p, Long.parseLong(a1.replace("#", "")), null);
                } catch (NumberFormatException e) {
                    plugin.lang().send(p, "admin.help");
                }
            }
            default -> plugin.lang().send(sender, "admin.help");
        }
    }

    private void history(CommandSender sender, PlayerProfile who, int page) {
        var repo = plugin.market().repo();
        plugin.async(() -> repo.history(who.uuid(), (page - 1) * HISTORY_PAGE, HISTORY_PAGE), rows -> {
            plugin.lang().send(sender, "admin.history.header", Placeholder.unparsed("player", who.name()),
                    Placeholder.unparsed("page", Integer.toString(page)));
            if (rows.isEmpty()) {
                plugin.lang().send(sender, "admin.history.empty");
                return;
            }
            for (Listing l : rows) {
                boolean sold = who.uuid().equals(l.seller());
                String key = l.status() == Listing.Status.SOLD ? (sold ? "admin.history.sold" : "admin.history.bought")
                        : "admin.history.other";
                Instant when = l.soldAt() != null ? l.soldAt() : l.createdAt();
                plugin.lang().send(sender, key, Placeholder.unparsed("id", Long.toString(l.id())),
                        Placeholder.unparsed("when", WHEN.format(when)),
                        Placeholder.unparsed("item", l.itemName()),
                        Placeholder.unparsed("amount", Integer.toString(l.amount())),
                        plugin.market().money("price", l.price()),
                        Placeholder.unparsed("each", Prices.format(l.unitPrice())),
                        Placeholder.unparsed("other", sold ? nullToDash(l.buyerName()) : l.sellerName()),
                        Placeholder.unparsed("status", l.status().name().toLowerCase(Locale.ROOT)));
            }
            if (rows.size() == HISTORY_PAGE) {
                // Parsed (not unparsed) so they fill in the click command; names are [A-Za-z0-9_].
                plugin.lang().send(sender, "admin.history.footer", Placeholder.parsed("player", who.name()),
                        Placeholder.parsed("next", Integer.toString(page + 1)));
            }
        }, sender);
    }

    private void lookup(CommandSender sender, String name, java.util.function.Consumer<PlayerProfile> then) {
        ProfileRepository profiles = new ProfileRepository(RapidCore.get().database());
        plugin.async(() -> profiles.findByName(name), found -> {
            Optional<PlayerProfile> p = found;
            if (p.isEmpty()) {
                plugin.lang().send(sender, "errors.unknown-player", Placeholder.unparsed("player", name));
            } else {
                then.accept(p.get());
            }
        }, sender);
    }

    private String hours() {
        return String.join(", ", plugin.settings().durations().stream().map(d -> d.toHours() + "h").toList());
    }

    private static String nullToDash(String s) {
        return s == null ? "-" : s;
    }

    @Override
    public Collection<String> suggest(CommandSourceStack stack, String[] args) {
        boolean admin = stack.getSender().hasPermission("rapid.market.admin");
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return Stream.concat(SUBS.stream(), admin ? Stream.of("admin") : Stream.empty())
                    .filter(s -> s.startsWith(prefix)).toList();
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("sell") && args.length == 3) {
            return plugin.settings().durations().stream().map(d -> d.toHours() + "h")
                    .filter(s -> s.startsWith(args[2].toLowerCase(Locale.ROOT))).toList();
        }
        if (sub.equals("collect") && args.length == 2) {
            return Stream.of("all").filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (sub.equals("admin") && admin) {
            if (args.length == 2) {
                return ADMIN_SUBS.stream().filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
            }
            if (args.length == 3 && !args[1].equalsIgnoreCase("remove")) {
                return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                        .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(args[2].toLowerCase(Locale.ROOT))).toList();
            }
        }
        return List.of();
    }
}
