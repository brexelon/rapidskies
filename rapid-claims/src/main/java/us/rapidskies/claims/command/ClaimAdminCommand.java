package us.rapidskies.claims.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import us.rapidskies.claims.ClaimService;
import us.rapidskies.claims.RapidClaims;
import us.rapidskies.claims.data.ClaimRepository.PlayerRow;
import us.rapidskies.claims.model.ChunkKey;
import us.rapidskies.claims.model.Claim;
import us.rapidskies.claims.model.Owner;
import us.rapidskies.claims.model.Town;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.common.staff.StaffActions;
import us.rapidskies.core.RapidCore;

/**
 * {@code /claimadmin} ({@code /ca}; PLAN.md §4.1, §9): inspect, transfer, unclaim or restore
 * claims and towns. Every action that changes something is logged to {@code staff_actions}.
 *
 * <ul>
 *   <li>{@code bypass}: build and open anything in claims, and ignore entry denials</li>
 *   <li>{@code info}: the chunk you stand in, with its owner's trust list and state</li>
 *   <li>{@code unclaim}: release the chunk you stand in (any owner but a town's center)</li>
 *   <li>{@code unclaimall <player>|town:<name>}: release everything an owner holds</li>
 *   <li>{@code transfer <player>|town:<name>}: give the chunk you stand in to another owner</li>
 *   <li>{@code restore <town>}: lift a town out of ruins with a day of upkeep paid</li>
 *   <li>{@code allowance <player> <+n|-n>}: grant or take back personal chunks</li>
 *   <li>{@code deletetown <town> confirm}: delete a town (bank paid to its mayor)</li>
 * </ul>
 */
public final class ClaimAdminCommand implements BasicCommand {

    private static final List<String> SUBS = List.of("bypass", "info", "unclaim", "unclaimall", "transfer", "restore",
            "allowance", "deletetown");

    private final RapidClaims plugin;
    private final StaffActions staffActions;

    public ClaimAdminCommand(RapidClaims plugin) {
        this.plugin = plugin;
        this.staffActions = new StaffActions(RapidCore.get().database());
    }

    @Override
    public String permission() {
        return "rapid.claims.admin";
    }

    private Lang lang() {
        return plugin.lang();
    }

    /** Writes the staff log row off the main thread. */
    private void log(Player staff, String action, UUID target, String detail) {
        String server = RapidCore.get().settings().serverId();
        plugin.async(() -> {
            staffActions.log(staff.getUniqueId(), action, target, server, detail);
            return null;
        }, done -> { }, null);
    }

    @Override
    public void execute(CommandSourceStack stack, String[] args) {
        if (!(stack.getSender() instanceof Player p)) {
            lang().send(stack.getSender(), "errors.players-only");
            return;
        }
        ChunkKey here = ClaimService.chunk(p.getLocation());
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        String a1 = Lookup.arg(args, 1);
        switch (sub) {
            case "bypass" -> {
                boolean on = plugin.service().toggleBypass(p.getUniqueId());
                lang().send(p, on ? "admin.bypass-on" : "admin.bypass-off");
                log(p, "claims-bypass", null, on ? "on" : "off");
            }
            case "info" -> info(p, here);
            case "unclaim" -> unclaim(p, here);
            case "unclaimall" -> owner(p, a1, owner -> unclaimAll(p, owner));
            case "transfer" -> owner(p, a1, owner -> transfer(p, here, owner));
            case "restore" -> town(p, a1).ifPresent(t -> restore(p, t));
            case "allowance" -> allowance(p, a1, Lookup.arg(args, 2));
            case "deletetown" -> town(p, a1).ifPresent(t -> {
                if (!Lookup.arg(args, 2).equalsIgnoreCase("confirm")) {
                    lang().send(p, "admin.delete-confirm", Placeholder.parsed("name", t.name()));
                    return;
                }
                plugin.async(() -> plugin.towns().deleteBlocking(t), paid -> {
                    plugin.towns().forget(t.id());
                    lang().send(p, "admin.deleted", Placeholder.unparsed("name", t.name()));
                    log(p, "town-delete", t.mayor(), t.name() + " (" + t.id() + "), bank " + paid + " to mayor");
                }, p);
            });
            default -> lang().send(p, "admin.help");
        }
    }

    private Optional<Town> town(Player p, String name) {
        Optional<Town> t = plugin.land().townByName(name);
        if (t.isEmpty()) {
            lang().send(p, "town.unknown", Placeholder.unparsed("name", name));
        }
        return t;
    }

    /** {@code town:<name>} or a player name, resolved to an owner. */
    private void owner(Player p, String arg, java.util.function.Consumer<Owner> then) {
        if (arg.isEmpty()) {
            lang().send(p, "admin.help");
        } else if (arg.toLowerCase(Locale.ROOT).startsWith("town:")) {
            town(p, arg.substring(5)).ifPresent(t -> then.accept(Owner.town(t.id())));
        } else {
            Lookup.player(plugin, p, arg, false, k -> then.accept(Owner.player(k.uuid())));
        }
    }

    private void info(Player p, ChunkKey here) {
        Optional<Claim> claim = plugin.land().claim(here);
        if (claim.isEmpty()) {
            lang().send(p, "claim.info-wilderness", Placeholder.unparsed("x", Integer.toString(here.x())),
                    Placeholder.unparsed("z", Integer.toString(here.z())));
            return;
        }
        Owner owner = claim.get().owner();
        var access = plugin.land().access(owner);
        String state;
        if (owner.isTown()) {
            state = plugin.land().town(owner.id()).map(t -> t.ruined() ? "RUINS since " + t.ruinsSince().orElse(null)
                    : "active, upkeep paid until " + t.upkeepPaidUntil()).orElse("unknown town");
        } else {
            PlayerRow row = plugin.lands().row(owner.id());
            state = row.ruinsSince().map(s -> "RUINS since " + s).orElse("active") + ", bought " + row.purchased()
                    + ", bonus " + row.bonus();
        }
        lang().send(p, "admin.info", Placeholder.component("owner", plugin.ownerName(owner)),
                Placeholder.unparsed("id", owner.format()),
                Placeholder.unparsed("chunks", Integer.toString(plugin.land().countOf(owner))),
                Placeholder.unparsed("state", state),
                Placeholder.unparsed("trusted", access.trust().isEmpty() ? "-" : String.join(", ",
                        access.trust().entrySet().stream().map(e -> plugin.names().name(e.getKey()) + " ("
                                + e.getValue().id() + ")").sorted().toList())),
                Placeholder.unparsed("denied", access.denied().isEmpty() ? "-" : String.join(", ",
                        access.denied().stream().map(plugin.names()::name).sorted().toList())),
                Placeholder.unparsed("by", plugin.names().name(claim.get().claimedBy())),
                Placeholder.unparsed("at", claim.get().claimedAt().toString()));
    }

    private void unclaim(Player p, ChunkKey here) {
        Optional<Claim> claim = plugin.land().claim(here);
        if (claim.isEmpty()) {
            lang().send(p, "claim.not-claimed");
            return;
        }
        Owner owner = claim.get().owner();
        if (owner.isTown() && plugin.land().town(owner.id()).map(t -> t.center().equals(here)).orElse(false)) {
            lang().send(p, "admin.center");
            return;
        }
        plugin.async(() -> plugin.repo().deleteClaim(here, owner), ok -> {
            plugin.land().remove(here);
            plugin.sync().chunk(here);
            lang().send(p, "admin.unclaimed", Placeholder.unparsed("x", Integer.toString(here.x())),
                    Placeholder.unparsed("z", Integer.toString(here.z())));
            log(p, "claim-unclaim", owner.type() == Owner.Type.PLAYER ? owner.id() : null,
                    here.format() + " from " + owner.format());
        }, p);
    }

    private void unclaimAll(Player p, Owner owner) {
        if (owner.isTown()) {
            lang().send(p, "admin.use-deletetown");
            return;
        }
        plugin.async(() -> plugin.lands().unclaimAllBlocking(owner), count -> {
            plugin.lands().forgetAll(owner);
            lang().send(p, "admin.unclaimed-all", Placeholder.unparsed("count", Integer.toString(count)),
                    Placeholder.component("owner", plugin.ownerName(owner)));
            log(p, "claim-unclaimall", owner.id(), count + " chunks of " + owner.format());
        }, p);
    }

    private void transfer(Player p, ChunkKey here, Owner to) {
        Optional<Claim> claim = plugin.land().claim(here);
        if (claim.isEmpty()) {
            lang().send(p, "claim.not-claimed");
            return;
        }
        Owner from = claim.get().owner();
        if (from.isTown() && plugin.land().town(from.id()).map(t -> t.center().equals(here)).orElse(false)) {
            lang().send(p, "admin.center");
            return;
        }
        plugin.async(() -> {
            plugin.repo().setOwner(List.of(here), to);
            return null;
        }, done -> {
            plugin.land().put(claim.get().withOwner(to));
            plugin.land().removePlot(here);
            plugin.sync().chunk(here);
            lang().send(p, "admin.transferred", Placeholder.component("owner", plugin.ownerName(to)));
            log(p, "claim-transfer", to.type() == Owner.Type.PLAYER ? to.id() : null,
                    here.format() + " " + from.format() + " -> " + to.format());
        }, p);
    }

    private void restore(Player p, Town t) {
        Town next = t.withUpkeep(Instant.now().plus(Duration.ofDays(1)), Town.State.ACTIVE, Optional.empty());
        plugin.async(() -> {
            plugin.repo().updateUpkeep(next);
            return null;
        }, done -> plugin.land().town(t.id()).ifPresent(now -> {
            plugin.land().putTown(now.withUpkeep(next.upkeepPaidUntil(), next.state(), next.ruinsSince()));
            plugin.sync().town(t.id());
            lang().send(p, "admin.restored", Placeholder.unparsed("name", t.name()));
            log(p, "town-restore", t.mayor(), t.name() + " (" + t.id() + ")");
        }), p);
    }

    private void allowance(Player p, String who, String change) {
        int delta;
        try {
            delta = Integer.parseInt(change.replace("+", ""));
        } catch (NumberFormatException e) {
            lang().send(p, "admin.help");
            return;
        }
        if (who.isEmpty() || delta == 0) {
            lang().send(p, "admin.help");
            return;
        }
        Lookup.player(plugin, p, who, false, k -> plugin.async(() -> plugin.repo().addAllowance(k.uuid(), 0, delta), row -> {
            plugin.lands().updateRow(row);
            plugin.sync().owner(Owner.player(k.uuid()));
            lang().send(p, "admin.allowance", Placeholder.unparsed("player", k.name()),
                    Placeholder.unparsed("bonus", Integer.toString(row.bonus())));
            log(p, "claim-allowance", k.uuid(), (delta > 0 ? "+" : "") + delta + " (bonus now " + row.bonus() + ")");
        }, p));
    }

    @Override
    public Collection<String> suggest(CommandSourceStack stack, String[] args) {
        if (args.length <= 1) {
            return Lookup.matching(Lookup.arg(args, 0), SUBS.stream());
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            return switch (sub) {
                case "unclaimall", "transfer", "allowance" -> Lookup.onlineNames(args[1]);
                case "restore", "deletetown" -> Lookup.matching(args[1], plugin.land().towns().stream().map(Town::name));
                default -> List.of();
            };
        }
        if (args.length == 3 && sub.equals("deletetown")) {
            return List.of("confirm");
        }
        if (args.length == 3 && sub.equals("allowance")) {
            return Stream.of("+1", "+5", "-1").toList();
        }
        return List.of();
    }
}
