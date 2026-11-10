package us.rapidskies.claims;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToIntFunction;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import us.rapidskies.claims.model.Claim;
import us.rapidskies.claims.model.ChunkKey;
import us.rapidskies.claims.model.Flag;
import us.rapidskies.claims.model.LandIndex;
import us.rapidskies.claims.model.Owner;
import us.rapidskies.claims.model.Role;
import us.rapidskies.claims.model.Town;
import us.rapidskies.claims.protect.Action;

/**
 * The claims API for other plugins (PLAN.md §4.1, engineering: QuickShop checks, Frontier
 * and Wildlands rules, Skyborne event regions, rapid-market stalls). Registered with
 * Bukkit's services manager, so a plugin that soft-depends on rapid-claims gets it with
 * {@link #find()}.
 *
 * <p>Everything here reads the in-memory index: safe and cheap on the main thread.
 */
public final class ClaimService {

    /** What claims say about PvP at a spot. */
    public enum PvpRule {
        /** Not claimed (or not a claimable world): the gamemode's own rules decide. */
        UNCLAIMED,
        /** A claim with PvP off (the default): nobody fights here. */
        DENY,
        /** A claim with PvP on: fighting is allowed here regardless of personal PvP toggles. */
        FORCE
    }

    private final LandIndex land;
    private final Set<String> worlds;
    private final Set<UUID> bypassing = ConcurrentHashMap.newKeySet();
    private final Map<Plugin, ToIntFunction<UUID>> allowanceSources = new ConcurrentHashMap<>();

    ClaimService(LandIndex land, Set<String> worlds) {
        this.land = land;
        this.worlds = Set.copyOf(worlds);
    }

    /** The running service, if rapid-claims is installed and enabled. */
    public static Optional<ClaimService> find() {
        var reg = Bukkit.getServicesManager().getRegistration(ClaimService.class);
        return reg == null ? Optional.empty() : Optional.of(reg.getProvider());
    }

    /** The index behind this service (towns, plots, trust lists). Read-only use outside rapid-claims. */
    public LandIndex land() {
        return land;
    }

    // ------------------------------------------------------------------ where

    public boolean claimable(World world) {
        return worlds.contains(world.getKey().asString());
    }

    public static ChunkKey chunk(Location at) {
        return ChunkKey.ofBlock(at.getWorld().getKey().asString(), at.getBlockX(), at.getBlockZ());
    }

    public static ChunkKey chunk(Block block) {
        return ChunkKey.ofBlock(block.getWorld().getKey().asString(), block.getX(), block.getZ());
    }

    public Optional<Claim> claimAt(Location at) {
        return land.claim(chunk(at));
    }

    public boolean isClaimed(Location at) {
        return land.isClaimed(chunk(at));
    }

    /** Whether the chunk at block {@code x, z} in {@code world} is claimed (for /rtp searches). */
    public boolean isClaimed(World world, int blockX, int blockZ) {
        return land.isClaimed(ChunkKey.ofBlock(world.getKey().asString(), blockX, blockZ));
    }

    // ------------------------------------------------------------------ who may do what

    /** {@code player}'s role at {@code at}: OWNER in the wilderness and while bypassing. */
    public Role role(Player player, Location at) {
        if (bypassing.contains(player.getUniqueId())) {
            return Role.OWNER;
        }
        return land.role(player.getUniqueId(), chunk(at));
    }

    public boolean can(Player player, Location at, Action action) {
        return action.allowedFor(role(player, at));
    }

    public PvpRule pvp(Location at) {
        Optional<Claim> claim = claimAt(at);
        if (claim.isEmpty()) {
            return PvpRule.UNCLAIMED;
        }
        return claim.get().flag(Flag.PVP) ? PvpRule.FORCE : PvpRule.DENY;
    }

    public Optional<Town> townOf(UUID player) {
        return land.townOf(player);
    }

    public Optional<Town> town(UUID id) {
        return land.town(id);
    }

    public Optional<Owner> ownerAt(Location at) {
        return land.owner(chunk(at));
    }

    // ------------------------------------------------------------------ staff bypass

    public boolean bypassing(UUID player) {
        return bypassing.contains(player);
    }

    /** Toggles {@code /claimadmin bypass}; returns the new state. */
    public boolean toggleBypass(UUID player) {
        if (bypassing.remove(player)) {
            return false;
        }
        bypassing.add(player);
        return true;
    }

    void clearBypass(UUID player) {
        bypassing.remove(player);
    }

    // ------------------------------------------------------------------ allowance from other plugins

    /**
     * Adds a source of extra personal claim chunks (PLAN.md §4.1: earned through Trade
     * levels). Called for online players when their allowance is shown or checked; must be
     * cheap and main-thread safe. Removed with {@link #unregisterAllowance} in onDisable.
     */
    public void registerAllowance(Plugin plugin, ToIntFunction<UUID> extraChunks) {
        allowanceSources.put(plugin, extraChunks);
    }

    public void unregisterAllowance(Plugin plugin) {
        allowanceSources.remove(plugin);
    }

    /** Extra chunks every registered source gives {@code player}. */
    int extraAllowance(UUID player) {
        int sum = 0;
        for (Map.Entry<Plugin, ToIntFunction<UUID>> e : allowanceSources.entrySet()) {
            try {
                sum += Math.max(0, e.getValue().applyAsInt(player));
            } catch (RuntimeException ex) {
                e.getKey().getSLF4JLogger().warn("Claim allowance source failed for {}: {}", player, ex.toString());
            }
        }
        return sum;
    }
}
