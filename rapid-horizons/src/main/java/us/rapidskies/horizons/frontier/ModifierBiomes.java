package us.rapidskies.horizons.frontier;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Which biome a Frontier gets at each point, from vanilla's own climate parameters.
 *
 * <ul>
 *   <li><b>Overgrowth, Drought, Deep Dark</b> keep vanilla's terrain shape, so oceans,
 *       beaches, rivers and the caves are kept where vanilla puts them (coasts, rivers and
 *       ancient cities still make sense); everything else comes from the modifier's palette by
 *       temperature and humidity. Drought's oceans are warm ones without monuments: its seas
 *       are drained down to {@code frontier.drought.water-level}, and a monument's water
 *       would spill out over the dry seabed.</li>
 *   <li><b>Shattered Skies</b> has its own terrain: the sea far below the islands is ocean by
 *       temperature, and every island is land from a bright palette ({@link #skies}).</li>
 * </ul>
 *
 * <p>Every biome here exists in 1.20 (no pale garden, no dappled forest), so older
 * clients see the same world. Keys only: free of Bukkit's registries, so it's unit-tested.
 */
public final class ModifierBiomes {

    private ModifierBiomes() {
    }

    // Vanilla's climate bands (net.minecraft.world.level.biome.OverworldBiomeBuilder).
    private static final double MUSHROOM = -1.05;
    private static final double DEEP_OCEAN = -0.455;
    private static final double OCEAN = -0.19;
    private static final double COAST = -0.11;
    /** |weirdness| below this, inland, is a river valley. */
    private static final double RIVER = 0.05;
    /** Below the surface (depth) a point is a cave. */
    private static final double CAVE_DEPTH = 0.2;
    private static final double DEEP_DARK_DEPTH = 1.1;
    private static final double DEEP_DARK_EROSION = -0.225;
    /** Deep Dark: below this depth the caverns are deep dark, unless they're humid enough to be lush. */
    private static final double DEEP_DARK_SPREAD_DEPTH = 0.75;
    /** Shattered Skies: this far above the sea, the biome is the islands', not the ocean's. */
    static final int SKY_SEA_MARGIN = 12;

    /** Temperature band 0 (frozen) .. 4 (hot), like vanilla's. */
    static int band(double temperature) {
        if (temperature < -0.45) {
            return 0;
        }
        if (temperature < -0.15) {
            return 1;
        }
        if (temperature < 0.2) {
            return 2;
        }
        if (temperature < 0.55) {
            return 3;
        }
        return 4;
    }

    /** Humidity band 0 (arid) .. 4 (humid). */
    static int wetness(double humidity) {
        if (humidity < -0.35) {
            return 0;
        }
        if (humidity < -0.1) {
            return 1;
        }
        if (humidity < 0.1) {
            return 2;
        }
        if (humidity < 0.3) {
            return 3;
        }
        return 4;
    }

    /**
     * The biome key ({@code minecraft:dark_forest}) at a point with these climate parameters.
     * For Shattered Skies this is the islands' biome; the sea under them is {@link #skies}'.
     */
    public static String biome(Modifier modifier, double temperature, double humidity, double continentalness,
                               double erosion, double weirdness, double depth) {
        int t = band(temperature);
        int h = wetness(humidity);
        if (modifier == Modifier.SHATTERED_SKIES) {
            return skiesLand(t, h, weirdness);
        }
        if (depth > CAVE_DEPTH) {
            return cave(modifier, h, erosion, depth);
        }
        if (continentalness < MUSHROOM) {
            return switch (modifier) {
                case OVERGROWTH -> "minecraft:mushroom_fields";
                case DEEP_DARK -> "minecraft:deep_cold_ocean";
                default -> "minecraft:warm_ocean";
            };
        }
        if (continentalness < DEEP_OCEAN) {
            return switch (modifier) {
                case OVERGROWTH -> "minecraft:deep_lukewarm_ocean";
                case DEEP_DARK -> "minecraft:deep_cold_ocean";
                default -> "minecraft:warm_ocean";
            };
        }
        if (continentalness < OCEAN) {
            return switch (modifier) {
                case OVERGROWTH -> "minecraft:lukewarm_ocean";
                case DEEP_DARK -> "minecraft:cold_ocean";
                default -> "minecraft:warm_ocean";
            };
        }
        if (continentalness < COAST) {
            return switch (modifier) {
                case OVERGROWTH -> h >= 3 ? "minecraft:mangrove_swamp" : "minecraft:beach";
                case DEEP_DARK -> "minecraft:stony_shore";
                default -> "minecraft:beach";
            };
        }
        if (Math.abs(weirdness) < RIVER) {
            // Rivers run on, except in a Drought, where the valleys are dry desert.
            return modifier == Modifier.DROUGHT ? "minecraft:desert" : "minecraft:river";
        }
        return switch (modifier) {
            case OVERGROWTH -> overgrowth(t, h, erosion, weirdness);
            case DEEP_DARK -> barren(t, h, weirdness);
            default -> drought(t, h, erosion, weirdness);
        };
    }

    /**
     * Shattered Skies at a height: ocean within {@link #SKY_SEA_MARGIN} blocks of the sea's
     * surface (and below), the islands' palette above.
     */
    public static String skies(double temperature, double humidity, double weirdness, int y, int seaLevel) {
        int t = band(temperature);
        if (y < seaLevel + SKY_SEA_MARGIN) {
            return switch (t) {
                case 0, 1 -> "minecraft:cold_ocean";
                case 2 -> "minecraft:ocean";
                case 3 -> "minecraft:lukewarm_ocean";
                default -> "minecraft:warm_ocean";
            };
        }
        return skiesLand(t, wetness(humidity), weirdness);
    }

    private static String cave(Modifier modifier, int h, double erosion, double depth) {
        if (modifier == Modifier.DEEP_DARK) {
            // The caverns: the deep dark spreads far wider than vanilla's, with lush pockets.
            if (depth > DEEP_DARK_SPREAD_DEPTH) {
                return h >= 4 ? "minecraft:lush_caves" : "minecraft:deep_dark";
            }
            return h >= 3 ? "minecraft:lush_caves" : "minecraft:dripstone_caves";
        }
        if (depth > DEEP_DARK_DEPTH && erosion < DEEP_DARK_EROSION) {
            return "minecraft:deep_dark";
        }
        return modifier == Modifier.OVERGROWTH ? "minecraft:lush_caves" : "minecraft:dripstone_caves";
    }

    private static String overgrowth(int t, int h, double erosion, double weirdness) {
        return switch (t) {
            case 0 -> h >= 2 ? "minecraft:snowy_taiga" : "minecraft:old_growth_spruce_taiga";
            case 1 -> h >= 3 ? "minecraft:old_growth_pine_taiga" : "minecraft:old_growth_spruce_taiga";
            case 2 -> switch (h) {
                case 0 -> "minecraft:flower_forest";
                case 1 -> weirdness > 0 ? "minecraft:cherry_grove" : "minecraft:forest";
                case 2 -> "minecraft:old_growth_birch_forest";
                default -> "minecraft:dark_forest";
            };
            case 3 -> switch (h) {
                case 0, 1 -> "minecraft:dark_forest";
                case 2 -> erosion > 0.45 ? "minecraft:swamp" : "minecraft:birch_forest";
                default -> "minecraft:jungle";
            };
            default -> h >= 3 ? "minecraft:bamboo_jungle" : h >= 1 ? "minecraft:jungle" : "minecraft:sparse_jungle";
        };
    }

    private static String drought(int t, int h, double erosion, double weirdness) {
        if (erosion < -0.375) {
            // The highest, least eroded land: mesas.
            return weirdness > 0 ? "minecraft:wooded_badlands" : "minecraft:badlands";
        }
        if (erosion > 0.45) {
            return "minecraft:eroded_badlands";
        }
        if (h >= 3 || t <= 1) {
            return erosion < 0 ? "minecraft:savanna_plateau" : "minecraft:savanna";
        }
        if (h == 2) {
            return weirdness > 0.5 ? "minecraft:windswept_savanna" : "minecraft:savanna";
        }
        return "minecraft:desert";
    }

    /** Deep Dark's surface: open, thinly grown land; the riches are below. */
    private static String barren(int t, int h, double weirdness) {
        return switch (t) {
            case 0 -> "minecraft:snowy_plains";
            case 1 -> "minecraft:windswept_gravelly_hills";
            case 2 -> h <= 1 ? "minecraft:windswept_gravelly_hills" : "minecraft:plains";
            case 3 -> h >= 3 ? "minecraft:savanna" : "minecraft:plains";
            default -> weirdness > 0.5 ? "minecraft:windswept_savanna" : "minecraft:savanna";
        };
    }

    /** Shattered Skies' islands: meadows, groves and light woods. */
    private static String skiesLand(int t, int h, double weirdness) {
        return switch (t) {
            case 0 -> h >= 3 ? "minecraft:grove" : "minecraft:snowy_plains";
            case 1 -> h >= 3 ? "minecraft:old_growth_pine_taiga" : "minecraft:taiga";
            case 2 -> switch (h) {
                case 0 -> "minecraft:meadow";
                case 1 -> "minecraft:plains";
                case 2 -> "minecraft:flower_forest";
                case 3 -> "minecraft:birch_forest";
                default -> "minecraft:forest";
            };
            case 3 -> switch (h) {
                case 0 -> "minecraft:sunflower_plains";
                case 1 -> "minecraft:meadow";
                case 2 -> weirdness > 0 ? "minecraft:cherry_grove" : "minecraft:meadow";
                default -> "minecraft:cherry_grove";
            };
            default -> h >= 3 ? "minecraft:sparse_jungle" : "minecraft:savanna";
        };
    }

    /** Every biome {@link #biome} (and, for Shattered Skies, {@link #skies}) can return (BiomeProvider#getBiomes). */
    public static List<String> all(Modifier modifier) {
        Set<String> out = new LinkedHashSet<>();
        double[] temps = {-0.8, -0.3, 0, 0.4, 0.8};
        double[] hums = {-0.6, -0.2, 0, 0.2, 0.6};
        double[] conts = {-1.2, -0.6, -0.3, -0.15, 0.5};
        double[] erosions = {-0.8, -0.3, 0.2, 0.7};
        double[] weirds = {-0.7, 0, 0.3, 0.7};
        double[] depths = {0, 0.5, 0.9, 1.5};
        for (double t : temps) {
            for (double h : hums) {
                for (double w : weirds) {
                    if (modifier == Modifier.SHATTERED_SKIES) {
                        out.add(skies(t, h, w, 0, 64));
                        out.add(skies(t, h, w, 200, 64));
                        continue;
                    }
                    for (double c : conts) {
                        for (double e : erosions) {
                            for (double d : depths) {
                                out.add(biome(modifier, t, h, c, e, w, d));
                            }
                        }
                    }
                }
            }
        }
        return List.copyOf(out);
    }
}
