package us.rapidskies.claims;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import us.rapidskies.claims.data.ClaimRepository.PlayerRow;
import us.rapidskies.claims.model.Access;
import us.rapidskies.claims.model.ChunkKey;
import us.rapidskies.claims.model.Claim;
import us.rapidskies.claims.model.Flag;
import us.rapidskies.claims.model.LandIndex;
import us.rapidskies.claims.model.Owner;
import us.rapidskies.claims.model.Plot;
import us.rapidskies.claims.model.Role;
import us.rapidskies.claims.model.Town;
import us.rapidskies.claims.model.TownPermission;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.core.RapidCore;
import us.rapidskies.core.economy.ModeEconomy;

/**
 * Personal land (PLAN.md §4.1): claiming and unclaiming chunks, the claim allowance and
 * buying more with Crowns, trust lists, keeping players out, and flags. Town land goes
 * through {@link us.rapidskies.claims.town.TownService}, which reuses the trust, denial
 * and flag parts here.
 *
 * <p>Every change follows the same pattern: check against the index on the main thread,
 * write to the database off it, then apply to the index and announce it ({@link Sync}).
 */
public final class LandService implements Listener {

    /** Permission for staff whose own entry can't be denied (they still see the rules). */
    public static final String ENTRY_EXEMPT = "rapid.claims.entry-exempt";

    private final RapidClaims plugin;
    private final LandIndex land;
    private final Lang lang;
    private final Map<UUID, PlayerRow> rows = new ConcurrentHashMap<>();
    /** Chunks with a claim on its way to the database, so a double click can't claim twice. */
    private final Set<ChunkKey> pending = ConcurrentHashMap.newKeySet();
    /** Players with a chunk purchase in flight. */
    private final Set<UUID> buying = ConcurrentHashMap.newKeySet();

    LandService(RapidClaims plugin, Map<UUID, PlayerRow> loaded) {
        this.plugin = plugin;
        this.land = plugin.land();
        this.lang = plugin.lang();
        rows.putAll(loaded);
    }

    // ------------------------------------------------------------------ allowance

    /**
     * A player's personal chunk allowance.
     *
     * @param extra chunks from other plugins (Trade levels)
     */
    public record Allowance(int used, int starter, int playtime, int purchased, int bonus, int extra) {
        public int total() {
            return starter + playtime + purchased + bonus + extra;
        }

        public int left() {
            return Math.max(0, total() - used);
        }
    }

    public PlayerRow row(UUID player) {
        return rows.getOrDefault(player, PlayerRow.empty(player));
    }

    public void updateRow(PlayerRow row) {
        rows.put(row.player(), row);
    }

    public Allowance allowance(Player player) {
        ClaimSettings.Allowance a = plugin.settings().allowance();
        PlayerRow row = row(player.getUniqueId());
        // PLAY_ONE_MINUTE counts ticks played on this server (the name is historical).
        long minutes = player.getStatistic(Statistic.PLAY_ONE_MINUTE) / 1200L;
        return new Allowance(land.countOf(Owner.player(player.getUniqueId())), a.starter(), a.fromPlaytime(minutes),
                row.purchased(), row.bonus(), plugin.service().extraAllowance(player.getUniqueId()));
    }

    private TagResolver[] allowanceTags(Allowance a) {
        return new TagResolver[] {
            Placeholder.unparsed("used", Integer.toString(a.used())),
            Placeholder.unparsed("total", Integer.toString(a.total())),
            Placeholder.unparsed("left", Integer.toString(a.left()))};
    }

    // ------------------------------------------------------------------ claiming

    /** Whether the chunk is in a claimable world; tells the player if not. */
    public boolean checkClaimable(Player player, ChunkKey chunk) {
        if (!plugin.settings().worlds().contains(chunk.world())) {
            lang.send(player, "claim.not-claimable");
            return false;
        }
        return true;
    }

    /** {@code /claim}: claims {@code chunk} for the player themselves. */
    public void claim(Player player, ChunkKey chunk) {
        if (!checkClaimable(player, chunk)) {
            return;
        }
        UUID id = player.getUniqueId();
        Optional<Claim> existing = land.claim(chunk);
        if (existing.isPresent()) {
            if (existing.get().owner().equals(Owner.player(id))) {
                lang.send(player, "claim.already-yours");
            } else {
                lang.send(player, "claim.taken", Placeholder.component("owner", plugin.ownerName(existing.get().owner())));
            }
            return;
        }
        Allowance a = allowance(player);
        if (a.left() <= 0) {
            lang.send(player, "claim.no-allowance", allowanceTags(a));
            return;
        }
        if (!pending.add(chunk)) {
            return;
        }
        Claim claim = new Claim(chunk, Owner.player(id), plugin.settings().defaultFlags(), id, Instant.now());
        plugin.async(() -> {
            try {
                return plugin.repo().insertClaim(claim);
            } finally {
                pending.remove(chunk);
            }
        }, inserted -> {
            if (!inserted) {
                refreshChunk(chunk);
                lang.send(player, "claim.race-lost");
                return;
            }
            land.put(claim);
            plugin.sync().chunk(chunk);
            Allowance now = allowance(player);
            lang.send(player, "claim.claimed", Placeholder.unparsed("x", Integer.toString(chunk.x())),
                    Placeholder.unparsed("z", Integer.toString(chunk.z())), allowanceTags(now)[0],
                    allowanceTags(now)[1]);
            plugin.borders().flash(player, chunk);
        }, player);
    }

    /** {@code /claim unclaim}: gives up one of the player's own chunks. */
    public void unclaim(Player player, ChunkKey chunk) {
        Optional<Claim> claim = land.claim(chunk);
        if (claim.isEmpty()) {
            lang.send(player, "claim.not-claimed");
            return;
        }
        Owner owner = claim.get().owner();
        if (owner.isTown()) {
            lang.send(player, "claim.town-land-unclaim");
            return;
        }
        boolean staff = plugin.service().bypassing(player.getUniqueId());
        if (!owner.id().equals(player.getUniqueId()) && !staff) {
            lang.send(player, "claim.not-yours", Placeholder.component("owner", plugin.ownerName(owner)));
            return;
        }
        plugin.async(() -> plugin.repo().deleteClaim(chunk, owner), removed -> {
            if (removed) {
                land.remove(chunk);
                plugin.sync().chunk(chunk);
                lang.send(player, "claim.unclaimed", Placeholder.unparsed("x", Integer.toString(chunk.x())),
                        Placeholder.unparsed("z", Integer.toString(chunk.z())));
            } else {
                refreshChunk(chunk);
                lang.send(player, "claim.not-claimed");
            }
        }, player);
    }

    /** Re-reads one chunk after the database disagreed with the index (another server changed it). */
    public void refreshChunk(ChunkKey chunk) {
        plugin.async(() -> plugin.repo().claim(chunk), claim -> {
            claim.ifPresentOrElse(land::put, () -> land.remove(chunk));
        }, null);
    }

    /** Releases every chunk {@code owner} holds (ruins expiring, staff). Blocking: call off the main thread. */
    public int unclaimAllBlocking(Owner owner) throws SQLException {
        return plugin.repo().deleteAllClaims(owner);
    }

    /** Applies {@link #unclaimAllBlocking} to the index (main thread). */
    public void forgetAll(Owner owner) {
        for (ChunkKey k : land.chunksOf(owner)) {
            land.remove(k);
            plugin.sync().chunk(k);
        }
    }

    // ------------------------------------------------------------------ buying chunks

    public long priceFor(UUID player, int count) {
        ClaimSettings.Allowance a = plugin.settings().allowance();
        int already = row(player).purchased();
        long total = 0;
        for (int i = 0; i < count; i++) {
            total += a.price(already + i);
        }
        return total;
    }

    /** {@code /claim buy [n]}: shows the price; with {@code confirm}, buys. */
    public void buy(Player player, int count, boolean confirm) {
        ModeEconomy crowns = plugin.crowns();
        if (crowns == null) {
            lang.send(player, "errors.no-crowns");
            return;
        }
        ClaimSettings.Allowance a = plugin.settings().allowance();
        UUID id = player.getUniqueId();
        int already = row(id).purchased();
        if (count < 1 || already + count > a.purchaseMax()) {
            lang.send(player, "buy.limit", Placeholder.unparsed("max", Integer.toString(a.purchaseMax())),
                    Placeholder.unparsed("bought", Integer.toString(already)));
            return;
        }
        long price = priceFor(id, count);
        TagResolver[] tags = {
            Placeholder.parsed("count", Integer.toString(count)),
            Placeholder.unparsed("price", String.format("%,d", price)),
            Placeholder.unparsed("next", String.format("%,d", a.price(already + count)))};
        if (!confirm) {
            lang.send(player, "buy.quote", tags);
            return;
        }
        if (!buying.add(id)) {
            return;
        }
        String txn = "claim-buy:" + id + ":" + (already + count);
        plugin.async(() -> {
            try {
                ModeEconomy.Change paid = crowns.withdraw(id, price, "claim-purchase", txn);
                if (!paid.success()) {
                    return Optional.<PlayerRow>empty();
                }
                try {
                    return Optional.of(plugin.repo().addAllowance(id, count, 0));
                } catch (SQLException e) {
                    crowns.deposit(id, price, "claim-purchase-refund", txn + ":refund");
                    throw e;
                }
            } finally {
                buying.remove(id);
            }
        }, row -> {
            if (row.isEmpty()) {
                lang.send(player, "buy.too-poor", tags);
                return;
            }
            updateRow(row.get());
            plugin.sync().owner(Owner.player(id));
            lang.send(player, "buy.done", tags);
        }, player);
    }

    // ------------------------------------------------------------------ trust, denials

    /**
     * Whose personal trust list {@code /claim trust} edits: the owner of the personal land
     * the player stands on if they're a Co-owner there, otherwise their own.
     */
    public Owner managedOwner(Player player) {
        ChunkKey here = ClaimService.chunk(player.getLocation());
        Optional<Claim> claim = land.claim(here);
        if (claim.isPresent() && claim.get().owner().type() == Owner.Type.PLAYER
                && !claim.get().owner().id().equals(player.getUniqueId())
                && land.role(player.getUniqueId(), here).atLeast(Role.CO_OWNER)) {
            return claim.get().owner();
        }
        return Owner.player(player.getUniqueId());
    }

    /**
     * Sets {@code target}'s role on all of {@code owner}'s land ({@code null} removes it).
     * {@code actorIsOwner}: only the owner (or a town's mayor) may hand out Co-owner, and
     * Co-owners can't change other Co-owners.
     */
    public void trust(Player actor, Owner owner, boolean actorIsOwner, Names.Known target, Role role) {
        if (target.uuid().equals(owner.id()) || target.uuid().equals(actor.getUniqueId())) {
            lang.send(actor, "trust.self");
            return;
        }
        Access current = land.access(owner);
        Role had = current.trust().getOrDefault(target.uuid(), Role.VISITOR);
        if (!actorIsOwner && (role == Role.CO_OWNER || had == Role.CO_OWNER)) {
            lang.send(actor, "trust.owner-only");
            return;
        }
        if (role != null && current.isDenied(target.uuid())) {
            lang.send(actor, "trust.denied-first", Placeholder.unparsed("player", target.name()));
            return;
        }
        TagResolver who = Placeholder.unparsed("player", Access.EVERYONE.equals(target.uuid())
                ? lang.raw("trust.everyone") : target.name());
        plugin.async(() -> {
            plugin.repo().setTrust(owner, target.uuid(), role);
            return null;
        }, done -> {
            land.setAccess(owner, land.access(owner).withTrust(target.uuid(), role));
            plugin.sync().owner(owner);
            if (role == null) {
                lang.send(actor, "trust.removed", who, Placeholder.component("owner", plugin.ownerName(owner)));
            } else {
                lang.send(actor, "trust.set", who, Placeholder.component("owner", plugin.ownerName(owner)),
                        Placeholder.component("role", lang.get("role." + role.id())));
                Player online = Bukkit.getPlayer(target.uuid());
                if (online != null && !online.equals(actor)) {
                    lang.send(online, "trust.notify", Placeholder.component("owner", plugin.ownerName(owner)),
                            Placeholder.component("role", lang.get("role." + role.id())));
                }
            }
        }, actor);
    }

    /** Keeps {@code target} out of all of {@code owner}'s land, or lets them back in. */
    public void deny(Player actor, Owner owner, Names.Known target, boolean deny) {
        if (target.uuid().equals(owner.id()) || target.uuid().equals(actor.getUniqueId())
                || Access.EVERYONE.equals(target.uuid())) {
            lang.send(actor, "deny.invalid");
            return;
        }
        if (deny) {
            Role trusted = land.access(owner).trust().getOrDefault(target.uuid(), Role.VISITOR);
            boolean member = owner.isTown() && land.town(owner.id()).map(t -> t.isMember(target.uuid())).orElse(false);
            if (trusted != Role.VISITOR || member) {
                lang.send(actor, "deny.trusted", Placeholder.unparsed("player", target.name()));
                return;
            }
        }
        TagResolver who = Placeholder.unparsed("player", target.name());
        plugin.async(() -> {
            plugin.repo().setDenied(owner, target.uuid(), deny);
            return null;
        }, done -> {
            land.setAccess(owner, land.access(owner).withDenied(target.uuid(), deny));
            plugin.sync().owner(owner);
            lang.send(actor, deny ? "deny.set" : "deny.removed", who);
            Player online = Bukkit.getPlayer(target.uuid());
            if (deny && online != null) {
                lang.send(online, "deny.notify", Placeholder.component("owner", plugin.ownerName(owner)));
                ejectIfDenied(online);
            }
        }, actor);
    }

    /** Sends a player standing where they're not allowed to the spawn. */
    public void ejectIfDenied(Player player) {
        if (player.hasPermission(ENTRY_EXEMPT) || plugin.service().bypassing(player.getUniqueId())) {
            return;
        }
        if (land.isDenied(player.getUniqueId(), ClaimService.chunk(player.getLocation()))) {
            RapidCore.get().worlds().teleport(player);
        }
    }

    // ------------------------------------------------------------------ flags

    /** Whether {@code player} may change flags on {@code chunk}. */
    public boolean canManage(Player player, ChunkKey chunk) {
        if (plugin.service().bypassing(player.getUniqueId())) {
            return true;
        }
        Optional<Claim> claim = land.claim(chunk);
        if (claim.isEmpty()) {
            return false;
        }
        if (land.role(player.getUniqueId(), chunk).atLeast(Role.CO_OWNER)) {
            return true;
        }
        Owner owner = claim.get().owner();
        return owner.isTown() && land.plot(chunk).isEmpty()
                && land.town(owner.id()).map(t -> t.may(player.getUniqueId(), TownPermission.FLAGS)).orElse(false);
    }

    /**
     * The chunks a flag change reaches: {@code chunk} alone, or with {@code everywhere}
     * all the land it belongs with (an owner's claims, a town's common land, or one
     * resident's plots in a town).
     */
    public List<ChunkKey> flagScope(ChunkKey chunk, boolean everywhere) {
        if (!everywhere) {
            return List.of(chunk);
        }
        Claim claim = land.claim(chunk).orElseThrow();
        Optional<Plot> plot = land.plot(chunk);
        if (plot.isPresent()) {
            return land.plotsOf(plot.get().resident()).stream()
                    .filter(p -> p.town().equals(plot.get().town())).map(Plot::chunk).toList();
        }
        List<ChunkKey> out = new ArrayList<>();
        for (ChunkKey k : land.chunksOf(claim.owner())) {
            if (!claim.owner().isTown() || land.plot(k).isEmpty()) {
                out.add(k);
            }
        }
        return out;
    }

    /** Sets {@code flag} on {@code chunks}; {@code then} runs on the main thread after. */
    public void setFlag(Player player, Collection<ChunkKey> chunks, Flag flag, boolean on, Runnable then) {
        List<Claim> changed = new ArrayList<>();
        for (ChunkKey k : chunks) {
            land.claim(k).ifPresent(c -> changed.add(c.withFlags(flag.set(c.flags(), on))));
        }
        if (changed.isEmpty()) {
            return;
        }
        plugin.async(() -> {
            // One statement per distinct resulting value keeps chunks' other flags intact.
            Map<Integer, List<ChunkKey>> byFlags = new java.util.HashMap<>();
            for (Claim c : changed) {
                byFlags.computeIfAbsent(c.flags(), f -> new ArrayList<>()).add(c.chunk());
            }
            for (var e : byFlags.entrySet()) {
                plugin.repo().setFlags(e.getValue(), e.getKey());
            }
            return null;
        }, done -> {
            for (Claim c : changed) {
                land.put(c);
                plugin.sync().chunk(c.chunk());
            }
            lang.send(player, on ? "flags.switched-on" : "flags.switched-off",
                    Placeholder.component("flag", lang.get("flag." + flag.id() + ".name")),
                    Placeholder.unparsed("count", Integer.toString(changed.size())));
            then.run();
        }, player);
    }

    // ------------------------------------------------------------------ events

    /** Coming back clears the player's land from ruins (PLAN.md §4.1: reclaim by logging in). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        if (row(id).ruinsSince().isEmpty()) {
            return;
        }
        plugin.async(() -> {
            plugin.repo().setPlayerRuins(id, null);
            return plugin.repo().player(id);
        }, row -> {
            updateRow(row);
            plugin.sync().owner(Owner.player(id));
            lang.send(player, "ruins.restored");
        }, null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        plugin.service().clearBypass(event.getPlayer().getUniqueId());
    }

    /** For ruins and admin views: the town owning {@code chunk}, if any. */
    public Optional<Town> townAt(ChunkKey chunk) {
        return land.owner(chunk).filter(Owner::isTown).flatMap(o -> land.town(o.id()));
    }
}
