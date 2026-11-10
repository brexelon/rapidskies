package us.rapidskies.claims.model;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * An owner's trust list and the players they keep out, for all of their land. Immutable:
 * a change replaces it in the cache.
 *
 * @param trust  player to role; {@link #EVERYONE} gives a role to all players
 * @param denied players who may not enter
 */
public record Access(Map<UUID, Role> trust, Set<UUID> denied) {

    /** The trust-list entry meaning "every player" ({@code /claim trust * trusted}). */
    public static final UUID EVERYONE = new UUID(0L, 0L);

    public static final Access NONE = new Access(Map.of(), Set.of());

    public Access {
        trust = Map.copyOf(trust);
        denied = Set.copyOf(denied);
    }

    /** The role {@code player} is trusted with: their own entry or the everyone entry, whichever is higher. */
    public Role roleOf(UUID player) {
        Role own = trust.getOrDefault(player, Role.VISITOR);
        Role all = trust.getOrDefault(EVERYONE, Role.VISITOR);
        return Role.max(own, all);
    }

    public boolean isDenied(UUID player) {
        return denied.contains(player);
    }

    public Access withTrust(UUID player, Role role) {
        Map<UUID, Role> next = new java.util.HashMap<>(trust);
        if (role == null || role == Role.VISITOR) {
            next.remove(player);
        } else {
            next.put(player, role);
        }
        return new Access(next, denied);
    }

    public Access withDenied(UUID player, boolean deny) {
        Set<UUID> next = new java.util.HashSet<>(denied);
        if (deny) {
            next.add(player);
        } else {
            next.remove(player);
        }
        return new Access(trust, next);
    }
}
