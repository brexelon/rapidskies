package us.rapidskies.claims.model;

import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A town (PLAN.md §4.1). Immutable: every change replaces it in the cache, so the async chat
 * thread (town tags) and the main thread can read it without locks.
 *
 * @param id        the town's uuid, also its bank account in the Crowns ledger
 * @param center    the town center chunk; town chunks connect back to it
 * @param warp      "world, x, y, z, yaw, pitch", once unlocked and set
 * @param members   every member and their role, the mayor included
 * @param roles     per-role settings (the mayor's isn't used: mayors may do everything)
 * @param completed ids of finished Town Projects
 */
public record Town(UUID id, String name, String tag, TagColor tagColor, UUID mayor, ChunkKey center,
                   Optional<String> warp, Instant upkeepPaidUntil, State state, Optional<Instant> ruinsSince,
                   Map<UUID, TownRole> members, Map<TownRole, RoleSettings> roles, Set<String> completed) {

    public static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{3,24}");
    public static final Pattern TAG = Pattern.compile("[A-Z0-9]{2,4}");

    public enum State {
        ACTIVE,
        /** Missed upkeep for too long: still protected for a grace period, then unclaimed. */
        RUINS
    }

    /** What a town role may do, and what it may do on the town's land outside plots. */
    public record RoleSettings(Set<TownPermission> permissions, Role land) {
        public RoleSettings {
            permissions = permissions.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(permissions));
        }
    }

    public Town {
        members = Map.copyOf(members);
        roles = Map.copyOf(roles);
        completed = Set.copyOf(completed);
    }

    /** Defaults for a new town; the mayor changes them with {@code /town perms}. */
    public static Map<TownRole, RoleSettings> defaultRoles() {
        Map<TownRole, RoleSettings> out = new EnumMap<>(TownRole.class);
        out.put(TownRole.ASSISTANT, new RoleSettings(EnumSet.of(TownPermission.INVITE, TownPermission.KICK,
                TownPermission.CLAIM, TownPermission.PLOTS, TownPermission.FLAGS, TownPermission.TRUST,
                TownPermission.PROMOTE, TownPermission.PROJECTS, TownPermission.WARP), Role.BUILDER));
        out.put(TownRole.RESIDENT, new RoleSettings(EnumSet.of(TownPermission.INVITE), Role.BUILDER));
        out.put(TownRole.RECRUIT, new RoleSettings(EnumSet.noneOf(TownPermission.class), Role.TRUSTED));
        return out;
    }

    public boolean isMember(UUID player) {
        return members.containsKey(player);
    }

    public Optional<TownRole> roleOf(UUID player) {
        return Optional.ofNullable(members.get(player));
    }

    /** Whether {@code player} is a member allowed to do {@code permission}. */
    public boolean may(UUID player, TownPermission permission) {
        TownRole role = members.get(player);
        if (role == null) {
            return false;
        }
        if (role == TownRole.MAYOR) {
            return true;
        }
        RoleSettings s = roles.get(role);
        return s != null && s.permissions().contains(permission);
    }

    /** The claim role a member has on town land outside plots; VISITOR for outsiders. */
    public Role landRole(UUID player) {
        TownRole role = members.get(player);
        if (role == null) {
            return Role.VISITOR;
        }
        if (role == TownRole.MAYOR) {
            return Role.OWNER;
        }
        RoleSettings s = roles.get(role);
        return s == null ? Role.VISITOR : s.land();
    }

    public boolean ruined() {
        return state == State.RUINS;
    }

    // ------------------------------------------------------------------ copies with one change

    public Town withMembers(Map<UUID, TownRole> next) {
        return new Town(id, name, tag, tagColor, mayor, center, warp, upkeepPaidUntil, state, ruinsSince, next, roles,
                completed);
    }

    public Town withMember(UUID player, TownRole role) {
        Map<UUID, TownRole> next = new HashMap<>(members);
        if (role == null) {
            next.remove(player);
        } else {
            next.put(player, role);
        }
        return withMembers(next);
    }

    public Town withMayor(UUID newMayor) {
        Map<UUID, TownRole> next = new HashMap<>(members);
        next.put(mayor, TownRole.ASSISTANT);
        next.put(newMayor, TownRole.MAYOR);
        return new Town(id, name, tag, tagColor, newMayor, center, warp, upkeepPaidUntil, state, ruinsSince, next,
                roles, completed);
    }

    public Town withRole(TownRole role, RoleSettings settings) {
        Map<TownRole, RoleSettings> next = new EnumMap<>(TownRole.class);
        next.putAll(roles);
        next.put(role, settings);
        return new Town(id, name, tag, tagColor, mayor, center, warp, upkeepPaidUntil, state, ruinsSince, members,
                next, completed);
    }

    public Town withTag(String newTag, TagColor color) {
        return new Town(id, name, newTag, color, mayor, center, warp, upkeepPaidUntil, state, ruinsSince, members,
                roles, completed);
    }

    public Town withName(String newName) {
        return new Town(id, newName, tag, tagColor, mayor, center, warp, upkeepPaidUntil, state, ruinsSince, members,
                roles, completed);
    }

    public Town withWarp(Optional<String> newWarp) {
        return new Town(id, name, tag, tagColor, mayor, center, newWarp, upkeepPaidUntil, state, ruinsSince, members,
                roles, completed);
    }

    public Town withUpkeep(Instant paidUntil, State newState, Optional<Instant> newRuinsSince) {
        return new Town(id, name, tag, tagColor, mayor, center, warp, paidUntil, newState, newRuinsSince, members,
                roles, completed);
    }

    public Town withCompleted(Set<String> next) {
        return new Town(id, name, tag, tagColor, mayor, center, warp, upkeepPaidUntil, state, ruinsSince, members,
                roles, next);
    }
}
