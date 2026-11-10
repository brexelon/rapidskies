package us.rapidskies.claims.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import us.rapidskies.claims.ClaimService;
import us.rapidskies.claims.RapidClaims;
import us.rapidskies.claims.gui.ClaimMapMenu;
import us.rapidskies.claims.gui.ProjectsMenu;
import us.rapidskies.claims.model.Owner;
import us.rapidskies.claims.model.Role;
import us.rapidskies.claims.model.TagColor;
import us.rapidskies.claims.model.Town;
import us.rapidskies.claims.model.TownPermission;
import us.rapidskies.claims.model.TownRole;
import us.rapidskies.claims.town.Project;
import us.rapidskies.common.lang.Lang;

/**
 * {@code /town} (aliases {@code /t}, {@code /towns}; PLAN.md §4.1). Without arguments it
 * shows your town. Subcommands: {@code create <name> <TAG>}, {@code info [town]},
 * {@code list}, {@code invite}, {@code join}, {@code leave}, {@code kick}, {@code rank},
 * {@code mayor}, {@code perms}, {@code deposit}, {@code withdraw}, {@code claim},
 * {@code unclaim}, {@code map}, {@code plot <player>|clear}, {@code trust}, {@code untrust},
 * {@code deny}, {@code undeny}, {@code tag}, {@code color}, {@code rename}, {@code warp [town]},
 * {@code setwarp}, {@code vault}, {@code projects}, {@code project start|donate},
 * {@code chat}, {@code disband}, {@code help}.
 */
public final class TownCommand implements BasicCommand {

    private static final List<String> SUBS = List.of("create", "info", "list", "invite", "join", "leave", "kick",
            "rank", "mayor", "perms", "deposit", "withdraw", "claim", "unclaim", "map", "plot", "trust", "untrust",
            "deny", "undeny", "tag", "color", "rename", "warp", "setwarp", "vault", "projects", "project", "chat",
            "disband", "help");

    private final RapidClaims plugin;

    public TownCommand(RapidClaims plugin) {
        this.plugin = plugin;
    }

    @Override
    public String permission() {
        return "rapid.command.town";
    }

    private Lang lang() {
        return plugin.lang();
    }

    @Override
    public void execute(CommandSourceStack stack, String[] args) {
        if (!(stack.getSender() instanceof Player p)) {
            if (args.length >= 1 && args[0].equalsIgnoreCase("list")) {
                plugin.towns().list(stack.getSender());
            } else {
                lang().send(stack.getSender(), "errors.players-only");
            }
            return;
        }
        var towns = plugin.towns();
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        String a1 = Lookup.arg(args, 1);
        String a2 = Lookup.arg(args, 2);
        boolean confirm = Lookup.rest(args, 1).stream().anyMatch(s -> s.equalsIgnoreCase("confirm"));
        switch (sub) {
            case "" -> towns.mine(p).ifPresentOrElse(t -> towns.info(p, t), () -> lang().send(p, "town.help"));
            case "info" -> {
                if (a1.isEmpty()) {
                    towns.mine(p).ifPresent(t -> towns.info(p, t));
                } else {
                    named(p, a1).ifPresent(t -> towns.info(p, t));
                }
            }
            case "list" -> towns.list(p);
            case "create", "found", "new" -> {
                if (a1.isEmpty() || a2.isEmpty()) {
                    lang().send(p, "town.create-usage", Placeholder.unparsed("price",
                            String.format("%,d", plugin.settings().towns().foundCost())));
                } else {
                    towns.found(p, a1, a2);
                }
            }
            case "invite", "add" -> needs(p, a1, () -> Lookup.player(plugin, p, a1, false, k -> towns.invite(p, k)));
            case "join", "accept" -> needs(p, a1, () -> towns.join(p, a1));
            case "leave" -> towns.leave(p);
            case "kick", "remove" -> needs(p, a1, () -> Lookup.player(plugin, p, a1, false, k -> towns.kick(p, k)));
            case "rank", "promote", "demote", "role" -> {
                Optional<TownRole> role = TownRole.parse(a2);
                if (a1.isEmpty() || role.isEmpty()) {
                    lang().send(p, "town.rank-usage");
                } else {
                    Lookup.player(plugin, p, a1, false, k -> towns.setRank(p, k, role.get()));
                }
            }
            case "mayor" -> needs(p, a1, () -> Lookup.player(plugin, p, a1, false,
                    k -> towns.transferMayor(p, k, confirm)));
            case "perms", "permissions" -> towns.perms(p, Lookup.rest(args, 1).toArray(String[]::new));
            case "deposit" -> amount(p, a1, n -> towns.deposit(p, n));
            case "withdraw" -> amount(p, a1, n -> towns.withdraw(p, n));
            case "claim" -> towns.claim(p, ClaimService.chunk(p.getLocation()));
            case "unclaim" -> towns.unclaim(p, ClaimService.chunk(p.getLocation()));
            case "map" -> new ClaimMapMenu(plugin, p, true).open(p);
            case "plot" -> {
                if (a1.equalsIgnoreCase("clear") || a1.equalsIgnoreCase("release")) {
                    towns.clearPlot(p);
                } else {
                    needs(p, a1, () -> Lookup.player(plugin, p, a1, false, k -> towns.assignPlot(p, k)));
                }
            }
            case "trust", "untrust" -> trust(p, sub.equals("untrust"), a1, a2);
            case "deny", "undeny" -> deny(p, sub.equals("deny"), a1);
            case "tag" -> needs(p, a1, () -> towns.setTag(p, a1, TagColor.parse(a2)));
            case "color", "colour" -> {
                Optional<TagColor> c = TagColor.parse(String.join("_", Lookup.rest(args, 1)));
                if (c.isEmpty()) {
                    lang().send(p, "town.color-usage", Placeholder.unparsed("colors",
                            String.join(", ", Stream.of(TagColor.values()).map(TagColor::tag).toList())));
                } else {
                    towns.setTag(p, null, c);
                }
            }
            case "rename" -> needs(p, a1, () -> towns.rename(p, a1, confirm));
            case "warp", "spawn", "home" -> towns.warp(p, a1.isEmpty() ? Optional.empty() : named(p, a1));
            case "setwarp", "sethome" -> towns.setWarp(p);
            case "vault" -> plugin.vault().open(p);
            case "projects" -> towns.mine(p).ifPresent(t -> ProjectsMenu.open(plugin, p, t));
            case "project" -> project(p, args);
            case "chat", "c" -> towns.toggleChat(p);
            case "disband", "delete" -> towns.disband(p, confirm);
            default -> lang().send(p, "town.help");
        }
    }

    private Optional<Town> named(Player p, String name) {
        Optional<Town> t = plugin.land().townByName(name);
        if (t.isEmpty()) {
            lang().send(p, "town.unknown", Placeholder.unparsed("name", name));
        }
        return t;
    }

    private void needs(Player p, String arg, Runnable then) {
        if (arg.isEmpty()) {
            lang().send(p, "town.help");
        } else {
            then.run();
        }
    }

    private void amount(Player p, String arg, java.util.function.LongConsumer then) {
        try {
            long n = Long.parseLong(arg.replace(",", "").replace("_", ""));
            if (n > 0) {
                then.accept(n);
                return;
            }
        } catch (NumberFormatException ignored) {
            // falls through to the usage message
        }
        lang().send(p, "town.bank-usage");
    }

    private void trust(Player p, boolean remove, String who, String roleName) {
        plugin.towns().mine(p).ifPresent(t -> {
            if (!t.may(p.getUniqueId(), TownPermission.TRUST)) {
                lang().send(p, "town.no-permission", Placeholder.unparsed("permission", TownPermission.TRUST.id()));
                return;
            }
            if (who.isEmpty()) {
                lang().send(p, remove ? "trust.untrust-usage" : "trust.usage");
                return;
            }
            Role role = remove ? null : Role.parse(roleName.isEmpty() ? "trusted" : roleName).filter(Role::grantable)
                    .orElse(null);
            if (!remove && role == null) {
                lang().send(p, "trust.bad-role");
                return;
            }
            boolean mayor = t.mayor().equals(p.getUniqueId());
            Lookup.player(plugin, p, who, true, k -> plugin.lands().trust(p, Owner.town(t.id()), mayor, k, role));
        });
    }

    private void deny(Player p, boolean deny, String who) {
        plugin.towns().mine(p).ifPresent(t -> {
            if (!t.may(p.getUniqueId(), TownPermission.TRUST)) {
                lang().send(p, "town.no-permission", Placeholder.unparsed("permission", TownPermission.TRUST.id()));
                return;
            }
            if (who.isEmpty()) {
                lang().send(p, "deny.usage");
                return;
            }
            Lookup.player(plugin, p, who, false, k -> plugin.lands().deny(p, Owner.town(t.id()), k, deny));
        });
    }

    /** {@code /town project start <id>} or {@code /town project donate <id> crowns <amount>}. */
    private void project(Player p, String[] args) {
        Optional<Town> t = plugin.towns().mine(p);
        if (t.isEmpty()) {
            return;
        }
        String action = Lookup.arg(args, 1).toLowerCase(Locale.ROOT);
        Optional<Project> project = plugin.settings().project(Lookup.arg(args, 2).toLowerCase(Locale.ROOT));
        if (project.isEmpty()) {
            lang().send(p, "project.usage");
            return;
        }
        switch (action) {
            case "start" -> plugin.projects().start(p, t.get(), project.get());
            case "donate" -> {
                if (!Lookup.arg(args, 3).equalsIgnoreCase(Project.CROWNS)) {
                    lang().send(p, "project.usage");
                    return;
                }
                amount(p, Lookup.arg(args, 4), n -> plugin.projects().donateCrowns(p, t.get(), project.get(), n, () -> { }));
            }
            default -> lang().send(p, "project.usage");
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack stack, String[] args) {
        if (args.length <= 1) {
            return Lookup.matching(Lookup.arg(args, 0), SUBS.stream());
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        String last = args[args.length - 1];
        if (args.length == 2) {
            return switch (sub) {
                case "invite", "kick", "rank", "mayor", "trust", "untrust", "deny", "undeny" -> Lookup.onlineNames(last);
                case "plot" -> {
                    List<String> out = new ArrayList<>(Lookup.onlineNames(last));
                    out.add(0, "clear");
                    yield out;
                }
                case "join", "info", "warp" -> Lookup.matching(last, plugin.land().towns().stream().map(Town::name));
                case "perms" -> Lookup.matching(last, Stream.of("assistant", "resident", "recruit"));
                case "color" -> Lookup.matching(last, Stream.of(TagColor.values()).map(TagColor::tag));
                case "project" -> Lookup.matching(last, Stream.of("start", "donate"));
                case "disband" -> List.of("confirm");
                default -> List.of();
            };
        }
        if (args.length == 3) {
            return switch (sub) {
                case "rank" -> Lookup.matching(last, Stream.of("assistant", "resident", "recruit"));
                case "trust" -> Lookup.matching(last, Stream.of(Role.values()).filter(Role::grantable).map(Role::id));
                case "perms" -> Lookup.matching(last, Stream.concat(Stream.of("land"),
                        Stream.of(TownPermission.values()).map(TownPermission::id)));
                case "tag" -> Lookup.matching(last, Stream.of(TagColor.values()).map(TagColor::tag));
                case "project" -> Lookup.matching(last, plugin.settings().projects().stream().map(Project::id));
                case "mayor", "rename" -> List.of("confirm");
                default -> List.of();
            };
        }
        if (args.length == 4) {
            return switch (sub) {
                case "perms" -> args[2].equalsIgnoreCase("land")
                        ? Lookup.matching(last, Stream.of("visitor", "trusted", "member", "builder"))
                        : Lookup.matching(last, Stream.of("on", "off"));
                case "project" -> List.of(Project.CROWNS);
                default -> List.of();
            };
        }
        return List.of();
    }
}
