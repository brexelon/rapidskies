package us.rapidskies.horizons.frontier;

import java.util.Locale;
import java.util.Optional;

/**
 * What makes a month's Frontier play differently (PLAN.md §5.2). Each one has its own terrain
 * generator ({@code frontier.terrain}), registered with Multiverse-Core as
 * {@code rapid-horizons:<id>}, its biomes ({@link ModifierBiomes}) and its rules
 * ({@code ModifierEffects}). Every biome and every block they generate exists in 1.20, so
 * every client from 1.20 up sees the same world.
 */
public enum Modifier {
    /** Vanilla terrain under dense forests: mossy ground, extra trees, and a wider mix of hostile mobs. */
    OVERGROWTH("OAK_SAPLING"),
    /** Deserts, badlands and savanna; shrunken seas and dry riverbeds, no rain, and a hot sun. */
    DROUGHT("DEAD_BUSH"),
    /** Floating islands at two heights over a calm sea, their stone veined with rare ores. */
    SHATTERED_SKIES("FEATHER"),
    /** A thin, barren surface over huge caverns where the deep dark spreads; ores run richer below. */
    DEEP_DARK("SCULK");

    /** Material name of the menu and message icon (exists in 1.20). */
    public final String icon;

    Modifier(String icon) {
        this.icon = icon;
    }

    /** Lowercase: config, lang keys, the database value and the generator id. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** How the config writes it ({@code shattered-skies}). */
    public String configName() {
        return id().replace('_', '-');
    }

    public static Optional<Modifier> parse(String s) {
        if (s == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(s.trim().toUpperCase(Locale.ROOT).replace('-', '_')));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
