package us.rapidskies.core.cosmetic;

import java.util.Optional;

/** The cosmetic slots (PLAN.md §6.1, §6.2). A player wears one cosmetic per type. */
public enum CosmeticType {
    /** On the profile card (hover a name in chat), the sidebar and {@code %rapid_title%}. */
    TITLE("title"),
    /** A symbol in front of the name in chat. */
    TAG("tag"),
    /** The name's color in chat; named colors only. */
    NAME_COLOR("namecolor"),
    /** On the profile card and {@code %rapid_badge%}. */
    BADGE("badge"),
    /** Particles at the feet while walking. */
    TRAIL("trail"),
    /** Particles behind thrown and shot projectiles. */
    ARROW("arrow");

    private final String id;

    CosmeticType(String id) {
        this.id = id;
    }

    /** The id prefix and slot name ({@code title}). */
    public String id() {
        return id;
    }

    public static Optional<CosmeticType> byId(String id) {
        for (CosmeticType t : values()) {
            if (t.id.equalsIgnoreCase(id)) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }
}
