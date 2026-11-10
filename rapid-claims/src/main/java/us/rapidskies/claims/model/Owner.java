package us.rapidskies.claims.model;

import java.util.Objects;
import java.util.UUID;

/** Who owns a claim: a player, or a town (by the town's uuid). */
public record Owner(Type type, UUID id) {

    public enum Type {
        PLAYER,
        TOWN
    }

    public Owner {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
    }

    public static Owner player(UUID id) {
        return new Owner(Type.PLAYER, id);
    }

    public static Owner town(UUID id) {
        return new Owner(Type.TOWN, id);
    }

    public boolean isTown() {
        return type == Type.TOWN;
    }

    /** {@code PLAYER:<uuid>} or {@code TOWN:<uuid>}, the form used in Redis messages. */
    public String format() {
        return type.name() + ":" + id;
    }

    public static Owner parse(String s) {
        int colon = s.indexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException("expected TYPE:uuid, got '" + s + "'");
        }
        return new Owner(Type.valueOf(s.substring(0, colon)), UUID.fromString(s.substring(colon + 1)));
    }
}
