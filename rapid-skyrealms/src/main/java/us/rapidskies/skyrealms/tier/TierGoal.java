package us.rapidskies.skyrealms.tier;

import java.util.Locale;
import org.bukkit.Material;
import us.rapidskies.skyrealms.SkyRealmsSettings;

/**
 * One goal toward the next Realm Tier (PLAN.md §5.1: a quest-driven chain): a {@link Type}, an
 * optional material, and a target. Written in the config as {@code "mine cobblestone 1000"} or
 * {@code "level 10"}.
 *
 * <p>{@link #id()} is how progress is stored ({@code mine:cobblestone}, {@code level}), so it
 * must stay the same while islands are working on a tier.
 */
public record TierGoal(Type type, Material material, long target) {

    public enum Type {
        /** Island level (SuperiorSkyblock2), read live; nothing stored. */
        LEVEL(false),
        /** Blocks from the island's generator broken. */
        MINE(true),
        /** Grown crops and plants harvested on the island. */
        HARVEST(true),
        /** Coins earned from /shop sales. */
        SELL(false),
        /** Fish caught from the island. */
        FISH(false),
        /** Expeditions contributed to. */
        EXPEDITION(false),
        /** Trade Route deliveries made as the seller. */
        ROUTE(false);

        private final boolean needsMaterial;

        Type(boolean needsMaterial) {
            this.needsMaterial = needsMaterial;
        }

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public TierGoal {
        if (target < 1) {
            throw new IllegalArgumentException("a goal's target must be at least 1");
        }
        if (type.needsMaterial != (material != null)) {
            throw new IllegalArgumentException(type.id() + (type.needsMaterial ? " needs a block" : " takes no block"));
        }
    }

    public static TierGoal parse(String text) {
        String[] p = text.trim().toLowerCase(Locale.ROOT).split("\\s+");
        Type type;
        try {
            type = Type.valueOf(p[0].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown goal '" + p[0] + "' in '" + text + "'", e);
        }
        int want = type.needsMaterial ? 3 : 2;
        if (p.length != want) {
            throw new IllegalArgumentException("'" + text + "' should be '" + type.id()
                    + (type.needsMaterial ? " <block>" : "") + " <amount>'");
        }
        Material material = type.needsMaterial ? SkyRealmsSettings.material(p[1]) : null;
        long target;
        try {
            target = Long.parseLong(p[p.length - 1].replace(",", "").replace("_", ""));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("amount in '" + text + "' isn't a number", e);
        }
        return new TierGoal(type, material, target);
    }

    /** The stored key: {@code mine:cobblestone}, {@code sell}. */
    public String id() {
        return material == null ? type.id() : type.id() + ":" + material.getKey().getKey();
    }

    /** Progress shown and compared, never above the target. */
    public long clamp(long progress) {
        return Math.max(0, Math.min(target, progress));
    }

    public boolean done(long progress) {
        return progress >= target;
    }

    /** Whether a {@code type} event about {@code m} counts toward this goal. */
    public boolean counts(Type type, Material m) {
        return this.type == type && (material == null || material == m);
    }
}
