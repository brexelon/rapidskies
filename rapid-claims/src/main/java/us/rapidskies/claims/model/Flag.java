package us.rapidskies.claims.model;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Per-chunk switches the owner (or a Co-owner) controls (PLAN.md §4.1). Stored as a bit
 * set in {@code claims.flags}; the bit values must never change. Explosions are always
 * blocked in claims and entry denial is per player ({@code claim_denied}), so neither is a flag.
 */
public enum Flag {
    /** PvP inside the chunk. Off by default; on makes it a forced-PvP area. */
    PVP(1),
    /** Monsters spawn naturally. */
    MOB_SPAWNING(2),
    /** Fire spreads and burns blocks. */
    FIRE_SPREAD(4),
    /** Leaves decay. */
    LEAF_DECAY(8);

    public final int bit;

    Flag(int bit) {
        this.bit = bit;
    }

    public boolean in(int flags) {
        return (flags & bit) != 0;
    }

    public int set(int flags, boolean on) {
        return on ? flags | bit : flags & ~bit;
    }

    public static int bits(Set<Flag> flags) {
        int out = 0;
        for (Flag f : flags) {
            out |= f.bit;
        }
        return out;
    }

    public static Set<Flag> of(int bits) {
        EnumSet<Flag> out = EnumSet.noneOf(Flag.class);
        for (Flag f : values()) {
            if (f.in(bits)) {
                out.add(f);
            }
        }
        return out;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public static Optional<Flag> parse(String s) {
        String n = s.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (Flag f : values()) {
            if (f.name().equals(n)) {
                return Optional.of(f);
            }
        }
        return switch (n) {
            case "MOBS", "MONSTERS" -> Optional.of(MOB_SPAWNING);
            case "FIRE" -> Optional.of(FIRE_SPREAD);
            case "LEAVES", "DECAY" -> Optional.of(LEAF_DECAY);
            default -> Optional.empty();
        };
    }
}
