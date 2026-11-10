package us.rapidskies.claims.model;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every claim, trust list, town and plot, in memory (PLAN.md §4.1, engineering: cached per
 * world, written through on change). Protection checks are O(1) map lookups by chunk.
 *
 * <p>Reads are safe from any thread (chat tags are read on the async chat thread): every
 * value is immutable and the maps are concurrent. Writes come from the main thread after
 * the database write succeeded (or from loading), so the index never shows something the
 * database doesn't have.
 *
 * <p>No Bukkit types here, so the rules are unit-tested directly.
 */
public final class LandIndex {

    private final Map<ChunkKey, Claim> claims = new ConcurrentHashMap<>();
    private final Map<Owner, Set<ChunkKey>> byOwner = new ConcurrentHashMap<>();
    private final Map<Owner, Access> access = new ConcurrentHashMap<>();
    private final Map<UUID, Town> towns = new ConcurrentHashMap<>();
    /** Lowercase name to town id. */
    private final Map<String, UUID> townNames = new ConcurrentHashMap<>();
    /** Player to the town they belong to. */
    private final Map<UUID, UUID> townOf = new ConcurrentHashMap<>();
    private final Map<ChunkKey, Plot> plots = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------ claims

    public Optional<Claim> claim(ChunkKey chunk) {
        return Optional.ofNullable(claims.get(chunk));
    }

    public boolean isClaimed(ChunkKey chunk) {
        return claims.containsKey(chunk);
    }

    public Optional<Owner> owner(ChunkKey chunk) {
        Claim c = claims.get(chunk);
        return c == null ? Optional.empty() : Optional.of(c.owner());
    }

    public void put(Claim claim) {
        Claim old = claims.put(claim.chunk(), claim);
        if (old != null && !old.owner().equals(claim.owner())) {
            unindex(old.owner(), old.chunk());
        }
        byOwner.computeIfAbsent(claim.owner(), o -> ConcurrentHashMap.newKeySet()).add(claim.chunk());
    }

    public Optional<Claim> remove(ChunkKey chunk) {
        Claim old = claims.remove(chunk);
        if (old != null) {
            unindex(old.owner(), chunk);
            plots.remove(chunk);
        }
        return Optional.ofNullable(old);
    }

    private void unindex(Owner owner, ChunkKey chunk) {
        byOwner.computeIfPresent(owner, (o, set) -> {
            set.remove(chunk);
            return set.isEmpty() ? null : set;
        });
    }

    /** A snapshot of the chunks {@code owner} holds. */
    public Set<ChunkKey> chunksOf(Owner owner) {
        Set<ChunkKey> set = byOwner.get(owner);
        return set == null ? Set.of() : Set.copyOf(set);
    }

    public int countOf(Owner owner) {
        Set<ChunkKey> set = byOwner.get(owner);
        return set == null ? 0 : set.size();
    }

    /** Every owner holding land (for the inactivity sweep). */
    public Set<Owner> owners() {
        return Set.copyOf(byOwner.keySet());
    }

    public int size() {
        return claims.size();
    }

    // ------------------------------------------------------------------ trust and denials

    public Access access(Owner owner) {
        return access.getOrDefault(owner, Access.NONE);
    }

    public void setAccess(Owner owner, Access a) {
        if (a.trust().isEmpty() && a.denied().isEmpty()) {
            access.remove(owner);
        } else {
            access.put(owner, a);
        }
    }

    // ------------------------------------------------------------------ towns

    public Optional<Town> town(UUID id) {
        return Optional.ofNullable(towns.get(id));
    }

    public Optional<Town> townByName(String name) {
        UUID id = townNames.get(name.toLowerCase(java.util.Locale.ROOT));
        return id == null ? Optional.empty() : town(id);
    }

    public Optional<Town> townOf(UUID player) {
        UUID id = townOf.get(player);
        return id == null ? Optional.empty() : town(id);
    }

    public Collection<Town> towns() {
        return List.copyOf(towns.values());
    }

    /** Adds or replaces a town, keeping the name and membership indexes in step. */
    public void putTown(Town town) {
        Town old = towns.put(town.id(), town);
        if (old != null) {
            townNames.remove(old.name().toLowerCase(java.util.Locale.ROOT), old.id());
            for (UUID member : old.members().keySet()) {
                if (!town.isMember(member)) {
                    townOf.remove(member, old.id());
                }
            }
        }
        townNames.put(town.name().toLowerCase(java.util.Locale.ROOT), town.id());
        for (UUID member : town.members().keySet()) {
            townOf.put(member, town.id());
        }
    }

    /** Drops a town and its plots from the index. Its claims must be removed separately. */
    public void removeTown(UUID id) {
        Town old = towns.remove(id);
        if (old == null) {
            return;
        }
        townNames.remove(old.name().toLowerCase(java.util.Locale.ROOT), id);
        for (UUID member : old.members().keySet()) {
            townOf.remove(member, id);
        }
        plots.values().removeIf(p -> p.town().equals(id));
        access.remove(Owner.town(id));
    }

    // ------------------------------------------------------------------ plots

    public Optional<Plot> plot(ChunkKey chunk) {
        return Optional.ofNullable(plots.get(chunk));
    }

    public void putPlot(Plot plot) {
        plots.put(plot.chunk(), plot);
    }

    public void removePlot(ChunkKey chunk) {
        plots.remove(chunk);
    }

    public List<Plot> plotsOf(UUID resident) {
        return plots.values().stream().filter(p -> p.resident().equals(resident)).toList();
    }

    public List<Plot> plotsIn(UUID town) {
        return plots.values().stream().filter(p -> p.town().equals(town)).toList();
    }

    // ------------------------------------------------------------------ rules

    /**
     * What {@code player} may do in {@code chunk}. Wilderness gives {@link Role#OWNER}
     * (nothing is protected there). Staff bypass is the caller's business.
     *
     * <ul>
     *   <li>Personal land: the owner is OWNER; anyone else gets their trust-list role.</li>
     *   <li>Town land outside plots: the mayor is OWNER; members get their role's land role;
     *       the town's trust list adds roles for outsiders.</li>
     *   <li>A plot: the resident is OWNER and their own trust list applies. Other members,
     *       the mayor included, and the town's trust list give at most TRUSTED there, so a
     *       resident's chests and builds are theirs alone.</li>
     * </ul>
     */
    public Role role(UUID player, ChunkKey chunk) {
        Claim claim = claims.get(chunk);
        if (claim == null) {
            return Role.OWNER;
        }
        Owner owner = claim.owner();
        if (owner.type() == Owner.Type.PLAYER) {
            return owner.id().equals(player) ? Role.OWNER : access(owner).roleOf(player);
        }
        Town town = towns.get(owner.id());
        Role townTrust = access(owner).roleOf(player);
        if (town == null) {
            return townTrust;
        }
        Plot plot = plots.get(chunk);
        if (plot != null) {
            if (plot.resident().equals(player)) {
                return Role.OWNER;
            }
            Role fromResident = access(Owner.player(plot.resident())).roleOf(player);
            Role fromTown = min(Role.max(townTrust, town.landRole(player)), Role.TRUSTED);
            return Role.max(fromResident, fromTown);
        }
        return Role.max(townTrust, town.landRole(player));
    }

    /** Whether {@code player} is kept out of {@code chunk} (the owner's or plot resident's deny list). */
    public boolean isDenied(UUID player, ChunkKey chunk) {
        Claim claim = claims.get(chunk);
        if (claim == null || role(player, chunk).atLeast(Role.TRUSTED)) {
            return false;
        }
        if (access(claim.owner()).isDenied(player)) {
            return true;
        }
        Plot plot = plots.get(chunk);
        return plot != null && access(Owner.player(plot.resident())).isDenied(player);
    }

    /**
     * Whether something may act from {@code from} into {@code to} (water and lava flowing,
     * pistons pushing, dispensers, trees and sculk growing, fire spreading): always into
     * wilderness, and into a claim only from land of the same owner. A town's plots count
     * as the town's.
     */
    public boolean crossAllowed(ChunkKey from, ChunkKey to) {
        if (from.equals(to)) {
            return true;
        }
        Claim target = claims.get(to);
        if (target == null) {
            return true;
        }
        Claim source = claims.get(from);
        return source != null && source.owner().equals(target.owner());
    }

    /** Whether {@code chunk} shares an edge with land the town holds. */
    public boolean touchesTown(UUID town, ChunkKey chunk) {
        Owner owner = Owner.town(town);
        for (ChunkKey n : chunk.neighbours()) {
            Claim c = claims.get(n);
            if (c != null && c.owner().equals(owner)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the town's land would still all connect back to its center if
     * {@code removed} were unclaimed (PLAN.md §4.1: town chunks are contiguous).
     */
    public boolean staysConnectedWithout(Town town, ChunkKey removed) {
        Owner owner = Owner.town(town.id());
        Set<ChunkKey> land = new HashSet<>(chunksOf(owner));
        land.remove(removed);
        if (land.isEmpty()) {
            return true;
        }
        if (!land.contains(town.center())) {
            return false;
        }
        Set<ChunkKey> seen = new HashSet<>();
        Deque<ChunkKey> queue = new ArrayDeque<>();
        queue.add(town.center());
        seen.add(town.center());
        while (!queue.isEmpty()) {
            for (ChunkKey n : queue.poll().neighbours()) {
                if (land.contains(n) && seen.add(n)) {
                    queue.add(n);
                }
            }
        }
        return seen.size() == land.size();
    }

    private static Role min(Role a, Role b) {
        return a.ordinal() <= b.ordinal() ? a : b;
    }
}
