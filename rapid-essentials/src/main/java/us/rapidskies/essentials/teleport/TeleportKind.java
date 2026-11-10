package us.rapidskies.essentials.teleport;

import java.util.Locale;

/** What a teleport is for: each kind has its own cooldown (config {@code teleport.cooldown-seconds}). */
public enum TeleportKind {
    BACK, HOME, WARP, SPAWN, TPA;

    /** The config key: {@code back}, {@code home}... */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The kind for a config key, or null. */
    public static TeleportKind parse(String id) {
        for (TeleportKind kind : values()) {
            if (kind.id().equalsIgnoreCase(id.trim())) {
                return kind;
            }
        }
        return null;
    }
}
