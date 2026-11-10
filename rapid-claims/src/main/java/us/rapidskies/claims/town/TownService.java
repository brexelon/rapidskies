package us.rapidskies.claims.town;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import us.rapidskies.claims.ClaimService;
import us.rapidskies.claims.ClaimSettings;
import us.rapidskies.claims.Names;
import us.rapidskies.claims.RapidClaims;
import us.rapidskies.claims.model.ChunkKey;
import us.rapidskies.claims.model.Claim;
import us.rapidskies.claims.model.LandIndex;
import us.rapidskies.claims.model.Owner;
import us.rapidskies.claims.model.Plot;
import us.rapidskies.claims.model.Role;
import us.rapidskies.claims.model.TagColor;
import us.rapidskies.claims.model.Town;
import us.rapidskies.claims.model.TownPermission;
import us.rapidskies.claims.model.TownRole;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.core.economy.ModeEconomy;
import us.rapidskies.core.world.SpawnPoint;

/**
 * Towns (PLAN.md §4.1): founding, invitations, roles and their permissions, the bank,
 * town land (contiguous from the center chunk, capped by members and Projects), plots,
 * tag and name, the warp, town chat and disbanding. Upkeep and ruins are {@link Upkeep};
 * Projects are {@link ProjectService}.
 *
 * <p>Like {@link us.rapidskies.claims.LandService}: checks on the main thread against the
 * index, database writes off it, then the new {@link Town} replaces the old one in the index.
 */
public final class TownService implements Listener {

    private record Invite(UUID town, UUID by, Instant expires) {
    }

    private final RapidClaims plugin;
    private final LandIndex land;
    private final Lang lang;
    private final Map<UUID, Invite> invites = new ConcurrentHashMap<>();
    /** Players whose plain chat goes to their town. */
    private final Set<UUID> townChat = ConcurrentHashMap.newKeySet();
    /** Towns with a bank operation in flight, so two withdrawals can't race on one bank. */
    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();

    public TownService(RapidClaims plugin) {
        this.plugin = plugin;
        this.land = plugin.land();
        this.lang = plugin.lang();
    }

    // ------------------------------------------------------------------ display

    public static Component colored(Town town, String text) {
        NamedTextColor color = NamedTextColor.NAMES.value(town.tagColor().tag());
        return Component.text(text, color == null ? NamedTextColor.WHITE : color);
    }

    /** The town tag in front of chat lines (rapid-core's ChatTags). Called on the async chat thread. */
    public Optional<Component> chatTag(UUID player) {
        return land.townOf(player).map(t -> lang.get("town.chat-tag", Placeholder.component("tag", colored(t, t.tag()))));
    }

    /** Extra chunks from finished Projects. */
    public int projectChunks(Town town) {
        int sum = 0;
        for (String id : town.completed()) {
            sum += plugin.settings().project(id).map(Project::extraChunks).orElse(0);
        }
        return sum;
    }

    public int chunkCap(Town town) {
        return plugin.settings().towns().chunkCap(town.members().size(), projectChunks(town));
    }

    /** One level per finished Project (PLAN.md §4.1: town level based on completed Projects). */
    public int level(Town town) {
        return town.completed().size();
    }

    public boolean unlocked(Town town, Project.Unlock.Kind kind) {
        return town.completed().stream().anyMatch(id -> plugin.settings().project(id)
                .map(p -> p.unlocks(kind)).orElse(false));
    }

    private TagResolver town(Town t) {
        return Placeholder.component("town", colored(t, t.name()));
    }

    private TagResolver player(String name) {
        return Placeholder.unparsed("player", name);
    }

    /** The player's town, or tells them they have none. */
    public Optional<Town> mine(Player p) {
        Optional<Town> t = land.townOf(p.getUniqueId());
        if (t.isEmpty()) {
            lang.send(p, "town.not-member");
        }
        return t;
    }

    /** Whether the player may do {@code perm} in their town; tells them if not. */
    private boolean may(Player p, Town t, TownPermission perm) {
        if (t.may(p.getUniqueId(), perm)) {
            return true;
        }
        lang.send(p, "town.no-permission", Placeholder.unparsed("permission", perm.id()));
        return false;
    }

    private boolean mayor(Player p, Town t) {
        if (t.mayor().equals(p.getUniqueId())) {
            return true;
        }
        lang.send(p, "town.mayor-only");
        return false;
    }

    /** Tells every online member of the town. */
    public void broadcast(Town t, String key, TagResolver... tags) {
        for (UUID id : t.members().keySet()) {
            Player online = Bukkit.getPlayer(id);
            if (online != null) {
                lang.send(online, key, tags);
            }
        }
    }

    private void apply(Town next) {
        land.putTown(next);
        plugin.sync().town(next.id());
    }

    // ------------------------------------------------------------------ founding

    public void found(Player p, String name, String tagInput) {
        UUID id = p.getUniqueId();
        if (land.townOf(id).isPresent()) {
            lang.send(p, "town.already-member");
            return;
        }
        String tag = tagInput.toUpperCase(java.util.Locale.ROOT);
        if (!Town.NAME.matcher(name).matches()) {
            lang.send(p, "town.bad-name");
            return;
        }
        if (!Town.TAG.matcher(tag).matches()) {
            lang.send(p, "town.bad-tag");
            return;
        }
        if (land.townByName(name).isPresent() || land.towns().stream().anyMatch(t -> t.tag().equals(tag))) {
            lang.send(p, "town.name-taken");
            return;
        }
        ChunkKey here = ClaimService.chunk(p.getLocation());
        if (!plugin.lands().checkClaimable(p, here)) {
            return;
        }
        Optional<Claim> existing = land.claim(here);
        boolean personal = existing.isPresent() && existing.get().owner().equals(Owner.player(id));
        if (existing.isPresent() && !personal) {
            lang.send(p, "town.found-taken");
            return;
        }
        ModeEconomy crowns = plugin.crowns();
        if (crowns == null) {
            lang.send(p, "errors.no-crowns");
            return;
        }
        long cost = plugin.settings().towns().foundCost();
        UUID townId = UUID.randomUUID();
        Instant now = Instant.now();
        // The first day of upkeep is included in the founding cost.
        Town town = new Town(townId, name, tag, TagColor.WHITE, id, here, Optional.empty(), now.plus(Duration.ofDays(1)),
                Town.State.ACTIVE, Optional.empty(), Map.of(id, TownRole.MAYOR), Town.defaultRoles(), Set.of());
        Claim center = personal ? existing.get().withOwner(Owner.town(townId))
                : new Claim(here, Owner.town(townId), plugin.settings().defaultFlags(), id, now);
        TagResolver price = Placeholder.unparsed("price", String.format("%,d", cost));
        plugin.async(() -> {
            if (cost > 0 && !crowns.withdraw(id, cost, "town-found", "town-found:" + townId).success()) {
                return Founded.TOO_POOR;
            }
            boolean ok;
            try {
                ok = plugin.repo().createTown(town, center, personal);
            } catch (SQLException e) {
                refund(crowns, id, cost, townId);
                throw e;
            }
            if (!ok) {
                refund(crowns, id, cost, townId);
                return Founded.TAKEN;
            }
            return Founded.OK;
        }, result -> {
            switch (result) {
                case TOO_POOR -> lang.send(p, "town.found-too-poor", price);
                case TAKEN -> lang.send(p, "town.found-race");
                case OK -> {
                    land.put(center);
                    apply(town);
                    plugin.sync().chunk(here);
                    plugin.borders().flash(p, here);
                    Bukkit.getServer().sendMessage(lang.get("town.founded-broadcast", town(town), player(p.getName())));
                    lang.send(p, "town.founded", town(town), price);
                }
            }
        }, p);
    }

    private enum Founded { OK, TOO_POOR, TAKEN }

    private static void refund(ModeEconomy crowns, UUID player, long cost, UUID townId) throws SQLException {
        if (cost > 0) {
            crowns.deposit(player, cost, "town-found-refund", "town-found-refund:" + townId);
        }
    }

    // ------------------------------------------------------------------ members

    public void invite(Player p, Names.Known target) {
        mine(p).ifPresent(t -> {
            if (!may(p, t, TownPermission.INVITE)) {
                return;
            }
            if (land.townOf(target.uuid()).isPresent()) {
                lang.send(p, "town.invite-in-town", player(target.name()));
                return;
            }
            Instant expires = Instant.now().plus(Duration.ofMinutes(plugin.settings().towns().inviteMinutes()));
            invites.put(target.uuid(), new Invite(t.id(), p.getUniqueId(), expires));
            lang.send(p, "town.invite-sent", player(target.name()));
            Player online = Bukkit.getPlayer(target.uuid());
            if (online != null) {
                lang.send(online, "town.invite-received", town(t), player(p.getName()),
                        Placeholder.parsed("name", t.name()),
                        Placeholder.unparsed("minutes", Integer.toString(plugin.settings().towns().inviteMinutes())));
            }
        });
    }

    public void join(Player p, String townName) {
        UUID id = p.getUniqueId();
        if (land.townOf(id).isPresent()) {
            lang.send(p, "town.already-member");
            return;
        }
        Optional<Town> t = land.townByName(townName);
        Invite invite = invites.get(id);
        if (t.isEmpty() || invite == null || !invite.town().equals(t.get().id()) || invite.expires().isBefore(Instant.now())) {
            lang.send(p, "town.no-invite");
            return;
        }
        invites.remove(id);
        UUID townId = t.get().id();
        plugin.async(() -> plugin.repo().addMember(townId, id, TownRole.RECRUIT), ok -> {
            Optional<Town> now = land.town(townId);
            if (!ok || now.isEmpty()) {
                lang.send(p, "town.join-failed");
                return;
            }
            Town next = now.get().withMember(id, TownRole.RECRUIT);
            apply(next);
            broadcast(next, "town.joined", player(p.getName()), town(next));
        }, p);
    }

    public void leave(Player p) {
        mine(p).ifPresent(t -> {
            if (t.mayor().equals(p.getUniqueId())) {
                lang.send(p, "town.mayor-cant-leave");
                return;
            }
            removeMember(p, t, p.getUniqueId(), p.getName(), "town.left");
        });
    }

    public void kick(Player p, Names.Known target) {
        mine(p).ifPresent(t -> {
            if (!may(p, t, TownPermission.KICK)) {
                return;
            }
            Optional<TownRole> theirs = t.roleOf(target.uuid());
            if (theirs.isEmpty()) {
                lang.send(p, "town.not-a-member", player(target.name()));
                return;
            }
            if (!t.roleOf(p.getUniqueId()).orElseThrow().above(theirs.get())) {
                lang.send(p, "town.rank-too-low");
                return;
            }
            removeMember(p, t, target.uuid(), target.name(), "town.kicked");
            Player online = Bukkit.getPlayer(target.uuid());
            if (online != null) {
                lang.send(online, "town.you-were-kicked", town(t));
            }
        });
    }

    private void removeMember(Player actor, Town t, UUID member, String name, String key) {
        plugin.async(() -> {
            plugin.repo().removeMember(t.id(), member);
            return null;
        }, done -> {
            land.town(t.id()).ifPresent(now -> {
                broadcast(now, key, player(name), town(now));
                apply(now.withMember(member, null));
            });
            for (Plot plot : land.plotsOf(member)) {
                if (plot.town().equals(t.id())) {
                    land.removePlot(plot.chunk());
                    plugin.sync().chunk(plot.chunk());
                }
            }
            townChat.remove(member);
        }, actor);
    }

    /** {@code /town rank <player> <role>}: promotions and demotions below the actor's own rank. */
    public void setRank(Player p, Names.Known target, TownRole role) {
        mine(p).ifPresent(t -> {
            if (!may(p, t, TownPermission.PROMOTE)) {
                return;
            }
            if (role == TownRole.MAYOR) {
                lang.send(p, "town.use-mayor-command");
                return;
            }
            Optional<TownRole> theirs = t.roleOf(target.uuid());
            if (theirs.isEmpty()) {
                lang.send(p, "town.not-a-member", player(target.name()));
                return;
            }
            TownRole mineRole = t.roleOf(p.getUniqueId()).orElseThrow();
            if (!mineRole.above(theirs.get()) || !mineRole.above(role)) {
                lang.send(p, "town.rank-too-low");
                return;
            }
            plugin.async(() -> {
                plugin.repo().setMemberRole(t.id(), target.uuid(), role);
                return null;
            }, done -> land.town(t.id()).ifPresent(now -> {
                Town next = now.withMember(target.uuid(), role);
                apply(next);
                broadcast(next, "town.rank-changed", player(target.name()),
                        Placeholder.component("role", lang.get("town.role." + role.id())));
            }), p);
        });
    }

    public void transferMayor(Player p, Names.Known target, boolean confirm) {
        mine(p).ifPresent(t -> {
            if (!mayor(p, t)) {
                return;
            }
            if (!t.isMember(target.uuid()) || target.uuid().equals(p.getUniqueId())) {
                lang.send(p, "town.not-a-member", player(target.name()));
                return;
            }
            if (!confirm) {
                lang.send(p, "town.mayor-confirm", player(target.name()), Placeholder.parsed("target", target.name()));
                return;
            }
            plugin.async(() -> {
                plugin.repo().setMayor(t.id(), p.getUniqueId(), target.uuid());
                return null;
            }, done -> land.town(t.id()).ifPresent(now -> {
                Town next = now.withMayor(target.uuid());
                apply(next);
                broadcast(next, "town.new-mayor", player(target.name()), town(next));
            }), p);
        });
    }

    /**
     * {@code /town perms <role> <permission|land> <value>}: the mayor changes what a role may
     * do. Without arguments it lists every role's settings.
     */
    public void perms(Player p, String[] args) {
        mine(p).ifPresent(t -> {
            if (args.length < 3) {
                for (TownRole r : List.of(TownRole.ASSISTANT, TownRole.RESIDENT, TownRole.RECRUIT)) {
                    Town.RoleSettings s = t.roles().get(r);
                    lang.send(p, "town.perms-line", Placeholder.component("role", lang.get("town.role." + r.id())),
                            Placeholder.unparsed("permissions", s.permissions().isEmpty() ? "-"
                                    : String.join(", ", s.permissions().stream().map(TownPermission::id).sorted().toList())),
                            Placeholder.component("land", lang.get("role." + s.land().id())));
                }
                lang.send(p, "town.perms-usage");
                return;
            }
            if (!mayor(p, t)) {
                return;
            }
            Optional<TownRole> role = TownRole.parse(args[0]).filter(r -> r != TownRole.MAYOR);
            if (role.isEmpty()) {
                lang.send(p, "town.perms-usage");
                return;
            }
            Town.RoleSettings current = t.roles().get(role.get());
            Town.RoleSettings next;
            if (args[1].equalsIgnoreCase("land")) {
                Optional<Role> land = Role.parse(args[2]).filter(r -> r.ordinal() <= Role.BUILDER.ordinal());
                if (land.isEmpty()) {
                    lang.send(p, "town.perms-bad-land");
                    return;
                }
                next = new Town.RoleSettings(current.permissions(), land.get());
            } else {
                Optional<TownPermission> perm = TownPermission.parse(args[1]);
                if (perm.isEmpty()) {
                    lang.send(p, "town.perms-usage");
                    return;
                }
                boolean on = args[2].equalsIgnoreCase("on") || args[2].equalsIgnoreCase("true")
                        || args[2].equalsIgnoreCase("yes");
                EnumSet<TownPermission> set = current.permissions().isEmpty() ? EnumSet.noneOf(TownPermission.class)
                        : EnumSet.copyOf(current.permissions());
                if (on) {
                    set.add(perm.get());
                } else {
                    set.remove(perm.get());
                }
                next = new Town.RoleSettings(set, current.land());
            }
            plugin.async(() -> {
                plugin.repo().setRole(t.id(), role.get(), next);
                return null;
            }, done -> land.town(t.id()).ifPresent(now -> {
                apply(now.withRole(role.get(), next));
                lang.send(p, "town.perms-changed", Placeholder.component("role", lang.get("town.role." + role.get().id())));
            }), p);
        });
    }

    // ------------------------------------------------------------------ bank

    public void balance(Town t, Consumer<Long> then, CommandSender who) {
        ModeEconomy crowns = plugin.crowns();
        if (crowns == null) {
            then.accept(0L);
            return;
        }
        plugin.async(() -> crowns.balance(t.id()), then, who);
    }

    public void deposit(Player p, long amount) {
        mine(p).ifPresent(t -> move(p, t, amount, true));
    }

    public void withdraw(Player p, long amount) {
        mine(p).ifPresent(t -> {
            if (may(p, t, TownPermission.WITHDRAW)) {
                move(p, t, amount, false);
            }
        });
    }

    private void move(Player p, Town t, long amount, boolean in) {
        ModeEconomy crowns = plugin.crowns();
        if (crowns == null) {
            lang.send(p, "errors.no-crowns");
            return;
        }
        if (amount <= 0) {
            lang.send(p, "town.bank-usage");
            return;
        }
        if (!busy.add(t.id())) {
            lang.send(p, "town.bank-busy");
            return;
        }
        UUID from = in ? p.getUniqueId() : t.id();
        UUID to = in ? t.id() : p.getUniqueId();
        TagResolver amt = Placeholder.unparsed("amount", String.format("%,d", amount));
        plugin.async(() -> {
            try {
                return crowns.transfer(from, to, amount, in ? "town-deposit" : "town-withdraw", null);
            } finally {
                busy.remove(t.id());
            }
        }, change -> {
            if (!change.success()) {
                lang.send(p, in ? "town.deposit-too-poor" : "town.withdraw-too-poor", amt);
                return;
            }
            land.town(t.id()).ifPresent(now -> broadcast(now, in ? "town.deposited" : "town.withdrew",
                    player(p.getName()), amt));
            if (in) {
                land.town(t.id()).filter(now -> now.upkeepPaidUntil().isBefore(Instant.now()))
                        .ifPresent(plugin.upkeep()::chargeNow);
            }
        }, p);
    }

    // ------------------------------------------------------------------ info

    public void info(CommandSender to, Town t) {
        balance(t, bank -> {
            ClaimSettings.Towns cfg = plugin.settings().towns();
            int chunks = land.countOf(Owner.town(t.id()));
            Instant paid = t.upkeepPaidUntil();
            Duration ahead = Duration.between(Instant.now(), paid);
            String upkeepState = ahead.isNegative()
                    ? lang.raw("town.info-behind").replace("<days>", Long.toString(-ahead.toDays()))
                    : lang.raw("town.info-paid").replace("<days>", Long.toString(ahead.toDays()));
            List<String> names = t.members().entrySet().stream()
                    .sorted(Map.Entry.comparingByValue(Comparator.comparingInt(Enum::ordinal)))
                    .map(e -> plugin.names().name(e.getKey())).toList();
            lang.send(to, "town.info", town(t),
                    Placeholder.component("tag", colored(t, t.tag())),
                    Placeholder.unparsed("mayor", plugin.names().name(t.mayor())),
                    Placeholder.unparsed("members", Integer.toString(t.members().size())),
                    Placeholder.unparsed("member-names", String.join(", ", names)),
                    Placeholder.unparsed("chunks", Integer.toString(chunks)),
                    Placeholder.unparsed("cap", Integer.toString(chunkCap(t))),
                    Placeholder.unparsed("level", Integer.toString(level(t))),
                    Placeholder.unparsed("bank", String.format("%,d", bank)),
                    Placeholder.unparsed("upkeep", String.format("%,d", cfg.upkeep(chunks))),
                    Placeholder.parsed("upkeep-state", upkeepState),
                    Placeholder.component("state", lang.get(t.ruined() ? "town.state-ruins" : "town.state-active")));
        }, to);
    }

    public void list(CommandSender to) {
        List<Town> all = new ArrayList<>(land.towns());
        all.sort(Comparator.comparingInt((Town t) -> t.members().size()).reversed().thenComparing(Town::name));
        lang.send(to, "town.list-header", Placeholder.unparsed("count", Integer.toString(all.size())));
        for (Town t : all.subList(0, Math.min(15, all.size()))) {
            lang.send(to, "town.list-line", town(t), Placeholder.component("tag", colored(t, t.tag())),
                    Placeholder.unparsed("members", Integer.toString(t.members().size())),
                    Placeholder.unparsed("level", Integer.toString(level(t))),
                    Placeholder.parsed("name", t.name()));
        }
    }

    // ------------------------------------------------------------------ land

    public void claim(Player p, ChunkKey chunk) {
        mine(p).ifPresent(t -> {
            if (!may(p, t, TownPermission.CLAIM) || !plugin.lands().checkClaimable(p, chunk)) {
                return;
            }
            if (t.ruined()) {
                lang.send(p, "town.ruins-no-claim");
                return;
            }
            Optional<Claim> existing = land.claim(chunk);
            boolean personal = existing.isPresent() && existing.get().owner().equals(Owner.player(p.getUniqueId()));
            if (existing.isPresent() && existing.get().owner().equals(Owner.town(t.id()))) {
                lang.send(p, "town.claim-already");
                return;
            }
            if (existing.isPresent() && !personal) {
                lang.send(p, "claim.taken", Placeholder.component("owner", plugin.ownerName(existing.get().owner())));
                return;
            }
            if (!chunk.world().equals(t.center().world()) || !land.touchesTown(t.id(), chunk)) {
                lang.send(p, "town.claim-not-adjacent");
                return;
            }
            int count = land.countOf(Owner.town(t.id()));
            if (count >= chunkCap(t)) {
                lang.send(p, "town.claim-cap", Placeholder.unparsed("cap", Integer.toString(chunkCap(t))));
                return;
            }
            Claim claim = personal ? existing.get().withOwner(Owner.town(t.id()))
                    : new Claim(chunk, Owner.town(t.id()), plugin.settings().defaultFlags(), p.getUniqueId(), Instant.now());
            plugin.async(() -> {
                if (personal) {
                    plugin.repo().setOwner(List.of(chunk), claim.owner());
                    return true;
                }
                return plugin.repo().insertClaim(claim);
            }, ok -> {
                if (!ok) {
                    plugin.lands().refreshChunk(chunk);
                    lang.send(p, "claim.race-lost");
                    return;
                }
                land.put(claim);
                plugin.sync().chunk(chunk);
                plugin.borders().flash(p, chunk);
                lang.send(p, "town.claimed", Placeholder.unparsed("x", Integer.toString(chunk.x())),
                        Placeholder.unparsed("z", Integer.toString(chunk.z())),
                        Placeholder.unparsed("count", Integer.toString(count + 1)),
                        Placeholder.unparsed("cap", Integer.toString(chunkCap(t))));
            }, p);
        });
    }

    public void unclaim(Player p, ChunkKey chunk) {
        mine(p).ifPresent(t -> {
            if (!may(p, t, TownPermission.CLAIM)) {
                return;
            }
            if (!land.owner(chunk).map(o -> o.equals(Owner.town(t.id()))).orElse(false)) {
                lang.send(p, "town.not-town-land");
                return;
            }
            if (chunk.equals(t.center())) {
                lang.send(p, "town.unclaim-center");
                return;
            }
            if (!land.staysConnectedWithout(t, chunk)) {
                lang.send(p, "town.unclaim-splits");
                return;
            }
            plugin.async(() -> plugin.repo().deleteClaim(chunk, Owner.town(t.id())), ok -> {
                if (ok) {
                    land.remove(chunk);
                    plugin.sync().chunk(chunk);
                    lang.send(p, "town.unclaimed", Placeholder.unparsed("x", Integer.toString(chunk.x())),
                            Placeholder.unparsed("z", Integer.toString(chunk.z())));
                } else {
                    plugin.lands().refreshChunk(chunk);
                }
            }, p);
        });
    }

    /** {@code /town plot <player>}: hands the chunk the actor stands on to a resident. */
    public void assignPlot(Player p, Names.Known resident) {
        mine(p).ifPresent(t -> {
            if (!may(p, t, TownPermission.PLOTS)) {
                return;
            }
            ChunkKey here = ClaimService.chunk(p.getLocation());
            if (!land.owner(here).map(o -> o.equals(Owner.town(t.id()))).orElse(false)) {
                lang.send(p, "town.not-town-land");
                return;
            }
            if (!t.isMember(resident.uuid())) {
                lang.send(p, "town.not-a-member", player(resident.name()));
                return;
            }
            Plot plot = new Plot(here, t.id(), resident.uuid());
            plugin.async(() -> {
                plugin.repo().setPlot(plot);
                return null;
            }, done -> {
                land.putPlot(plot);
                plugin.sync().chunk(here);
                lang.send(p, "town.plot-assigned", player(resident.name()));
                Player online = Bukkit.getPlayer(resident.uuid());
                if (online != null && !online.equals(p)) {
                    lang.send(online, "town.plot-received", Placeholder.unparsed("x", Integer.toString(here.x())),
                            Placeholder.unparsed("z", Integer.toString(here.z())));
                }
            }, p);
        });
    }

    /** {@code /town plot clear}: the town takes the chunk back (PLOTS), or the resident gives it up. */
    public void clearPlot(Player p) {
        ChunkKey here = ClaimService.chunk(p.getLocation());
        Optional<Plot> plot = land.plot(here);
        if (plot.isEmpty()) {
            lang.send(p, "town.no-plot-here");
            return;
        }
        boolean resident = plot.get().resident().equals(p.getUniqueId());
        boolean manager = land.town(plot.get().town()).map(t -> t.may(p.getUniqueId(), TownPermission.PLOTS))
                .orElse(false);
        if (!resident && !manager && !plugin.service().bypassing(p.getUniqueId())) {
            lang.send(p, "town.no-permission", Placeholder.unparsed("permission", TownPermission.PLOTS.id()));
            return;
        }
        plugin.async(() -> {
            plugin.repo().removePlot(here);
            return null;
        }, done -> {
            land.removePlot(here);
            plugin.sync().chunk(here);
            lang.send(p, "town.plot-cleared");
        }, p);
    }

    // ------------------------------------------------------------------ identity

    public void setTag(Player p, String tagInput, Optional<TagColor> color) {
        mine(p).ifPresent(t -> {
            if (!mayor(p, t)) {
                return;
            }
            String tag = tagInput == null ? t.tag() : tagInput.toUpperCase(java.util.Locale.ROOT);
            if (!Town.TAG.matcher(tag).matches()) {
                lang.send(p, "town.bad-tag");
                return;
            }
            if (!tag.equals(t.tag()) && land.towns().stream().anyMatch(o -> o.tag().equals(tag))) {
                lang.send(p, "town.name-taken");
                return;
            }
            Town next = t.withTag(tag, color.orElse(t.tagColor()));
            plugin.async(() -> plugin.repo().updateTownIdentity(next), ok -> {
                if (!ok) {
                    lang.send(p, "town.name-taken");
                    return;
                }
                land.town(t.id()).ifPresent(now -> {
                    Town applied = now.withTag(next.tag(), next.tagColor());
                    apply(applied);
                    broadcast(applied, "town.tag-changed", Placeholder.component("tag", colored(applied, applied.tag())));
                });
            }, p);
        });
    }

    public void rename(Player p, String name, boolean confirm) {
        mine(p).ifPresent(t -> {
            if (!mayor(p, t)) {
                return;
            }
            if (!Town.NAME.matcher(name).matches()) {
                lang.send(p, "town.bad-name");
                return;
            }
            Optional<Town> other = land.townByName(name);
            if (other.isPresent() && !other.get().id().equals(t.id())) {
                lang.send(p, "town.name-taken");
                return;
            }
            long cost = plugin.settings().towns().renameCost();
            TagResolver price = Placeholder.unparsed("price", String.format("%,d", cost));
            if (!confirm) {
                lang.send(p, "town.rename-confirm", price, Placeholder.parsed("name", name));
                return;
            }
            ModeEconomy crowns = plugin.crowns();
            if (crowns == null) {
                lang.send(p, "errors.no-crowns");
                return;
            }
            Town next = t.withName(name);
            plugin.async(() -> {
                // Paid from the town bank: a town expense.
                if (cost > 0 && !crowns.withdraw(t.id(), cost, "town-rename", null).success()) {
                    return Optional.of(false);
                }
                if (!plugin.repo().updateTownIdentity(next)) {
                    if (cost > 0) {
                        crowns.deposit(t.id(), cost, "town-rename-refund", null);
                    }
                    return Optional.<Boolean>empty();
                }
                return Optional.of(true);
            }, ok -> {
                if (ok.isEmpty()) {
                    lang.send(p, "town.name-taken");
                } else if (!ok.get()) {
                    lang.send(p, "town.rename-too-poor", price);
                } else {
                    land.town(t.id()).ifPresent(now -> {
                        Town applied = now.withName(name);
                        apply(applied);
                        broadcast(applied, "town.renamed", town(applied));
                    });
                }
            }, p);
        });
    }

    // ------------------------------------------------------------------ warp

    public void setWarp(Player p) {
        mine(p).ifPresent(t -> {
            if (!unlocked(t, Project.Unlock.Kind.WARP)) {
                lang.send(p, "town.warp-locked");
                return;
            }
            if (!may(p, t, TownPermission.WARP)) {
                return;
            }
            if (!land.owner(ClaimService.chunk(p.getLocation())).map(o -> o.equals(Owner.town(t.id()))).orElse(false)) {
                lang.send(p, "town.not-town-land");
                return;
            }
            Location l = p.getLocation();
            String point = new SpawnPoint(l.getWorld().getKey().asString(), l.getX(), l.getY(), l.getZ(), l.getYaw(),
                    l.getPitch()).format();
            plugin.async(() -> {
                plugin.repo().updateWarp(t.id(), Optional.of(point));
                return null;
            }, done -> land.town(t.id()).ifPresent(now -> {
                apply(now.withWarp(Optional.of(point)));
                lang.send(p, "town.warp-set");
            }), p);
        });
    }

    public void warp(Player p, Optional<Town> target) {
        Optional<Town> t = target.isPresent() ? target : mine(p);
        if (t.isEmpty()) {
            return;
        }
        if (t.get().warp().isEmpty()) {
            lang.send(p, unlocked(t.get(), Project.Unlock.Kind.WARP) ? "town.warp-unset" : "town.warp-locked");
            return;
        }
        if (!t.get().isMember(p.getUniqueId()) && land.access(Owner.town(t.get().id())).isDenied(p.getUniqueId())) {
            lang.send(p, "entry.denied", Placeholder.component("owner", plugin.ownerName(Owner.town(t.get().id()))));
            return;
        }
        SpawnPoint point = SpawnPoint.parse(t.get().warp().get());
        World world = Bukkit.getWorld(org.bukkit.NamespacedKey.fromString(point.world()));
        if (world == null) {
            lang.send(p, "town.warp-unset");
            return;
        }
        p.teleportAsync(new Location(world, point.x(), point.y(), point.z(), point.yaw(), point.pitch()),
                PlayerTeleportEvent.TeleportCause.PLUGIN);
        lang.send(p, "town.warped", town(t.get()));
    }

    // ------------------------------------------------------------------ disbanding

    public void disband(Player p, boolean confirm) {
        mine(p).ifPresent(t -> {
            if (!mayor(p, t)) {
                return;
            }
            if (!confirm) {
                lang.send(p, "town.disband-confirm", town(t));
                return;
            }
            plugin.async(() -> deleteBlocking(t), paidOut -> {
                forget(t.id());
                broadcast(t, "town.disbanded", town(t));
                Bukkit.getServer().sendMessage(lang.get("town.disbanded-broadcast", town(t)));
                if (paidOut > 0) {
                    lang.send(p, "town.disband-payout", Placeholder.unparsed("amount", String.format("%,d", paidOut)));
                }
            }, p);
        });
    }

    /**
     * Deletes a town (blocking): pays what's left in the bank to the mayor, then removes its
     * land, plots, members, projects and vault. Builds stay in the world. Returns the payout.
     */
    public long deleteBlocking(Town t) throws SQLException {
        ModeEconomy crowns = plugin.crowns();
        long left = 0;
        if (crowns != null) {
            left = crowns.balance(t.id());
            if (left > 0) {
                crowns.transfer(t.id(), t.mayor(), left, "town-disband", "town-disband:" + t.id());
            }
        }
        plugin.vault().discard(t.id());
        plugin.repo().deleteTown(t.id());
        return left;
    }

    /** Applies {@link #deleteBlocking} to the index (main thread). */
    public void forget(UUID town) {
        plugin.lands().forgetAll(Owner.town(town));
        land.town(town).ifPresent(t -> t.members().keySet().forEach(townChat::remove));
        land.removeTown(town);
        plugin.sync().town(town);
    }

    // ------------------------------------------------------------------ town chat

    public boolean toggleChat(Player p) {
        if (land.townOf(p.getUniqueId()).isEmpty()) {
            lang.send(p, "town.not-member");
            return false;
        }
        if (townChat.remove(p.getUniqueId())) {
            lang.send(p, "town.chat-off");
            return false;
        }
        townChat.add(p.getUniqueId());
        lang.send(p, "town.chat-on");
        return true;
    }

    /**
     * Sends a line to the player's town. Horizons runs on one server for now, so town chat
     * reaches members on this server; it moves to Redis with a second instance.
     */
    public void say(Player p, Component message) {
        Optional<Town> t = land.townOf(p.getUniqueId());
        if (t.isEmpty()) {
            lang.send(p, "town.not-member");
            return;
        }
        Component line = lang.get("town.chat-format", Placeholder.component("tag", colored(t.get(), t.get().tag())),
                Placeholder.component("role", lang.get("town.role-short." + t.get().roleOf(p.getUniqueId())
                        .orElse(TownRole.RECRUIT).id())),
                player(p.getName()), Placeholder.component("message", message));
        for (UUID id : t.get().members().keySet()) {
            Player online = Bukkit.getPlayer(id);
            if (online != null) {
                online.sendMessage(line);
            }
        }
        // In the console log like public chat, for moderation.
        Bukkit.getConsoleSender().sendMessage(line);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player p = event.getPlayer();
        if (!townChat.contains(p.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        Component message = event.message();
        Bukkit.getScheduler().runTask(plugin, () -> say(p, message));
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        land.townOf(p.getUniqueId()).ifPresent(t -> {
            if (t.ruined()) {
                lang.send(p, "town.ruins-warning", town(t));
            } else if (t.upkeepPaidUntil().isBefore(Instant.now()) && t.may(p.getUniqueId(), TownPermission.WITHDRAW)) {
                lang.send(p, "town.upkeep-behind", town(t));
            }
        });
        Invite invite = invites.get(p.getUniqueId());
        if (invite != null && invite.expires().isAfter(Instant.now())) {
            land.town(invite.town()).ifPresent(t -> lang.send(p, "town.invite-received", town(t),
                    player(plugin.names().name(invite.by())), Placeholder.parsed("name", t.name()),
                    Placeholder.unparsed("minutes", Long.toString(Math.max(1,
                            Duration.between(Instant.now(), invite.expires()).toMinutes())))));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        townChat.remove(event.getPlayer().getUniqueId());
    }
}
