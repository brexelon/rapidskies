package us.rapidskies.claims.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import us.rapidskies.claims.ClaimService;
import us.rapidskies.claims.LandService;
import us.rapidskies.claims.RapidClaims;
import us.rapidskies.claims.gui.ClaimMapMenu;
import us.rapidskies.claims.gui.FlagsMenu;
import us.rapidskies.claims.model.Access;
import us.rapidskies.claims.model.ChunkKey;
import us.rapidskies.claims.model.Claim;
import us.rapidskies.claims.model.Flag;
import us.rapidskies.claims.model.Owner;
import us.rapidskies.claims.model.Role;
import us.rapidskies.common.lang.Lang;

/**
 * {@code /claim} (aliases {@code /claims}, {@code /c}; PLAN.md §4.1): with no arguments
 * claims the chunk you stand in. Subcommands: {@code map}, {@code unclaim}, {@code info},
 * {@code list}, {@code allowance}, {@code buy [n] [confirm]}, {@code trust <player|*> [role]},
 * {@code untrust <player|*>}, {@code trusted}, {@code deny <player>}, {@code undeny <player>},
 * {@code flags}, {@code flag <flag> <on|off> [all]}, {@code show}, {@code help}.
 */
public final class ClaimCommand implements BasicCommand {

    private static final List<String> SUBS = List.of("map", "unclaim", "info", "list", "allowance", "buy", "trust",
            "untrust", "trusted", "deny", "undeny", "flags", "flag", "show", "help");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private final RapidClaims plugin;

    public ClaimCommand(RapidClaims plugin) {
        this.plugin = plugin;
    }

    @Override
    public String permission() {
        return "rapid.command.claim";
    }

    private Lang lang() {
        return plugin.lang();
    }

    @Override
    public void execute(CommandSourceStack stack, String[] args) {
        if (!(stack.getSender() instanceof Player p)) {
            lang().send(stack.getSender(), "errors.players-only");
            return;
        }
        ChunkKey here = ClaimService.chunk(p.getLocation());
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "" -> plugin.lands().claim(p, here);
            case "map", "gui" -> new ClaimMapMenu(plugin, p, false).open(p);
            case "unclaim", "abandon" -> plugin.lands().unclaim(p, here);
            case "info", "here" -> info(p, here);
            case "list" -> list(p);
            case "allowance", "limit", "chunks" -> allowance(p);
            case "buy" -> buy(p, args);
            case "trust" -> trust(p, args, false);
            case "untrust" -> trust(p, args, true);
            case "trusted" -> trusted(p);
            case "deny", "ban" -> deny(p, args, true);
            case "undeny", "unban" -> deny(p, args, false);
            case "flags" -> flags(p, here);
            case "flag" -> flag(p, here, args);
            case "show", "border", "borders" -> {
                plugin.borders().show(p);
                lang().send(p, "show.legend");
            }
            default -> lang().send(p, "claim.help");
        }
    }

    private void info(Player p, ChunkKey here) {
        Optional<Claim> claim = plugin.land().claim(here);
        if (!plugin.settings().worlds().contains(here.world())) {
            lang().send(p, "claim.not-claimable");
            return;
        }
        if (claim.isEmpty()) {
            lang().send(p, "claim.info-wilderness", Placeholder.unparsed("x", Integer.toString(here.x())),
                    Placeholder.unparsed("z", Integer.toString(here.z())));
            return;
        }
        Claim c = claim.get();
        Role role = plugin.service().role(p, p.getLocation());
        List<String> on = Flag.of(c.flags()).stream().map(f -> lang().raw("flag." + f.id() + ".name")).toList();
        lang().send(p, "claim.info", Placeholder.component("owner", plugin.ownerName(c.owner())),
                Placeholder.unparsed("x", Integer.toString(here.x())),
                Placeholder.unparsed("z", Integer.toString(here.z())),
                Placeholder.unparsed("since", DATE.format(c.claimedAt())),
                Placeholder.component("role", lang().get("role." + role.id())),
                Placeholder.parsed("flags", on.isEmpty() ? lang().raw("claim.info-no-flags") : String.join(", ", on)));
        plugin.land().plot(here).ifPresent(plot -> lang().send(p, "claim.info-plot",
                Placeholder.unparsed("resident", plugin.names().name(plot.resident()))));
    }

    private void list(Player p) {
        List<ChunkKey> mine = new ArrayList<>(plugin.land().chunksOf(Owner.player(p.getUniqueId())));
        mine.sort(java.util.Comparator.comparing(ChunkKey::world).thenComparingInt(ChunkKey::x)
                .thenComparingInt(ChunkKey::z));
        LandService.Allowance a = plugin.lands().allowance(p);
        lang().send(p, "claim.list-header", Placeholder.unparsed("used", Integer.toString(a.used())),
                Placeholder.unparsed("total", Integer.toString(a.total())));
        for (ChunkKey k : mine.subList(0, Math.min(20, mine.size()))) {
            lang().send(p, "claim.list-line", Placeholder.unparsed("x", Integer.toString(k.x())),
                    Placeholder.unparsed("z", Integer.toString(k.z())),
                    Placeholder.unparsed("bx", Integer.toString(k.centerBlockX())),
                    Placeholder.unparsed("bz", Integer.toString(k.centerBlockZ())));
        }
        if (mine.size() > 20) {
            lang().send(p, "claim.list-more", Placeholder.unparsed("count", Integer.toString(mine.size() - 20)));
        }
    }

    private void allowance(Player p) {
        LandService.Allowance a = plugin.lands().allowance(p);
        lang().send(p, "claim.allowance",
                Placeholder.unparsed("used", Integer.toString(a.used())),
                Placeholder.unparsed("total", Integer.toString(a.total())),
                Placeholder.unparsed("starter", Integer.toString(a.starter())),
                Placeholder.unparsed("playtime", Integer.toString(a.playtime())),
                Placeholder.unparsed("playtime-max", Integer.toString(plugin.settings().allowance().playtimeMax())),
                Placeholder.unparsed("hours", Integer.toString(plugin.settings().allowance().hoursPerChunk())),
                Placeholder.unparsed("purchased", Integer.toString(a.purchased())),
                Placeholder.unparsed("bonus", Integer.toString(a.bonus())),
                Placeholder.unparsed("extra", Integer.toString(a.extra())),
                Placeholder.unparsed("next-price", String.format("%,d", plugin.lands().priceFor(p.getUniqueId(), 1))));
    }

    private void buy(Player p, String[] args) {
        int count = 1;
        boolean confirm = false;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equalsIgnoreCase("confirm")) {
                confirm = true;
            } else {
                try {
                    count = Integer.parseInt(args[i]);
                } catch (NumberFormatException e) {
                    lang().send(p, "buy.usage");
                    return;
                }
            }
        }
        plugin.lands().buy(p, count, confirm);
    }

    private void trust(Player p, String[] args, boolean remove) {
        if (args.length < 2) {
            lang().send(p, remove ? "trust.untrust-usage" : "trust.usage");
            return;
        }
        Role role = Role.TRUSTED;
        if (!remove && args.length >= 3) {
            Optional<Role> parsed = Role.parse(args[2]).filter(Role::grantable);
            if (parsed.isEmpty()) {
                lang().send(p, "trust.bad-role");
                return;
            }
            role = parsed.get();
        }
        Owner owner = plugin.lands().managedOwner(p);
        boolean isOwner = owner.id().equals(p.getUniqueId());
        Role give = remove ? null : role;
        Lookup.player(plugin, p, args[1], true, target -> plugin.lands().trust(p, owner, isOwner, target, give));
    }

    private void trusted(Player p) {
        Owner owner = plugin.lands().managedOwner(p);
        Access access = plugin.land().access(owner);
        lang().send(p, "trust.list-header", Placeholder.component("owner", plugin.ownerName(owner)));
        if (access.trust().isEmpty()) {
            lang().send(p, "trust.list-empty");
        }
        for (Map.Entry<UUID, Role> e : access.trust().entrySet()) {
            lang().send(p, "trust.list-line", Placeholder.unparsed("player", Access.EVERYONE.equals(e.getKey())
                            ? lang().raw("trust.everyone") : plugin.names().name(e.getKey())),
                    Placeholder.component("role", lang().get("role." + e.getValue().id())));
        }
        if (!access.denied().isEmpty()) {
            lang().send(p, "deny.list", Placeholder.unparsed("players", String.join(", ",
                    access.denied().stream().map(plugin.names()::name).sorted().toList())));
        }
    }

    private void deny(Player p, String[] args, boolean deny) {
        if (args.length < 2) {
            lang().send(p, "deny.usage");
            return;
        }
        Owner owner = plugin.lands().managedOwner(p);
        Lookup.player(plugin, p, args[1], false, target -> plugin.lands().deny(p, owner, target, deny));
    }

    private void flags(Player p, ChunkKey here) {
        if (!plugin.land().isClaimed(here)) {
            lang().send(p, "claim.not-claimed");
        } else if (!plugin.lands().canManage(p, here)) {
            lang().send(p, "flags.not-allowed");
        } else {
            new FlagsMenu(plugin, here).open(p);
        }
    }

    private void flag(Player p, ChunkKey here, String[] args) {
        if (args.length < 3) {
            lang().send(p, "flags.usage");
            return;
        }
        Optional<Flag> flag = Flag.parse(args[1]);
        if (flag.isEmpty()) {
            lang().send(p, "flags.usage");
            return;
        }
        if (!plugin.land().isClaimed(here) || !plugin.lands().canManage(p, here)) {
            lang().send(p, "flags.not-allowed");
            return;
        }
        boolean on = args[2].equalsIgnoreCase("on") || args[2].equalsIgnoreCase("true") || args[2].equalsIgnoreCase("allow");
        boolean all = args.length > 3 && args[3].equalsIgnoreCase("all");
        plugin.lands().setFlag(p, plugin.lands().flagScope(here, all), flag.get(), on, () -> { });
    }

    @Override
    public Collection<String> suggest(CommandSourceStack stack, String[] args) {
        if (args.length <= 1) {
            return Lookup.matching(Lookup.arg(args, 0), SUBS.stream());
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            return switch (sub) {
                case "trust", "untrust" -> {
                    List<String> out = new ArrayList<>(Lookup.onlineNames(args[1]));
                    out.add(0, "*");
                    yield out;
                }
                case "deny", "undeny" -> Lookup.onlineNames(args[1]);
                case "flag" -> Lookup.matching(args[1], Stream.of(Flag.values()).map(Flag::id));
                case "buy" -> List.of("1", "5", "confirm");
                default -> List.of();
            };
        }
        if (args.length == 3) {
            return switch (sub) {
                case "trust" -> Lookup.matching(args[2], Stream.of(Role.values()).filter(Role::grantable).map(Role::id));
                case "flag" -> Lookup.matching(args[2], Stream.of("on", "off"));
                case "buy" -> List.of("confirm");
                default -> List.of();
            };
        }
        if (args.length == 4 && sub.equals("flag")) {
            return Lookup.matching(args[3], Stream.of("all"));
        }
        return List.of();
    }
}
