package us.rapidskies.horizons.frontier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import us.rapidskies.horizons.frontier.FrontierSchedule.Phase;

/** The rotation's calendar, the Wildlands ring, world keys and the modifiers' biomes (terrain: TerrainTest). */
class FrontierRulesTest {

    private static final Instant CLOSES = Instant.parse("2026-12-10T00:00:00Z");
    private static final Duration WEEK = Duration.ofDays(7);

    @Test
    void phasesOfAMonth() {
        assertEquals(Phase.OPEN, FrontierSchedule.phase(CLOSES.minus(Duration.ofDays(20)), CLOSES, WEEK));
        assertEquals(Phase.WARNING, FrontierSchedule.phase(CLOSES.minus(WEEK), CLOSES, WEEK),
                "the warning starts exactly a week out");
        assertEquals(Phase.WARNING, FrontierSchedule.phase(CLOSES.minusSeconds(1), CLOSES, WEEK));
        assertEquals(Phase.DUE, FrontierSchedule.phase(CLOSES, CLOSES, WEEK));
        assertEquals(Phase.DUE, FrontierSchedule.phase(CLOSES.plus(Duration.ofDays(3)), CLOSES, WEEK),
                "a server that was down past closing time rotates on start");
        assertEquals(Phase.OPEN, FrontierSchedule.phase(CLOSES.minusSeconds(1), CLOSES, Duration.ZERO));
    }

    @Test
    void modifiersNeverRepeatBackToBack() {
        List<Modifier> cycle = List.of(Modifier.OVERGROWTH, Modifier.DROUGHT);
        assertEquals(Modifier.OVERGROWTH, FrontierSchedule.next(cycle, Optional.empty()), "the first Frontier");
        assertEquals(Modifier.DROUGHT, FrontierSchedule.next(cycle, Optional.of(Modifier.OVERGROWTH)));
        assertEquals(Modifier.OVERGROWTH, FrontierSchedule.next(cycle, Optional.of(Modifier.DROUGHT)));
        assertEquals(Modifier.DROUGHT, FrontierSchedule.next(List.of(Modifier.DROUGHT), Optional.of(Modifier.DROUGHT)),
                "only one configured");
        assertEquals(Modifier.OVERGROWTH, FrontierSchedule.next(List.of(Modifier.OVERGROWTH),
                Optional.of(Modifier.DROUGHT)), "a modifier taken out of the cycle");
        assertThrows(IllegalArgumentException.class, () -> FrontierSchedule.next(List.of(), Optional.empty()));
    }

    @Test
    void chaptersGoOnePerFrontier() {
        List<String> chapters = List.of("chapter-1", "chapter-2");
        assertEquals(Optional.of("chapter-1"), FrontierSchedule.chapter(1, chapters));
        assertEquals(Optional.of("chapter-2"), FrontierSchedule.chapter(2, chapters));
        assertEquals(Optional.empty(), FrontierSchedule.chapter(3, chapters), "until the next one is added");
        assertEquals(Optional.empty(), FrontierSchedule.chapter(0, chapters));
    }

    @Test
    void theEndOpensOnTime() {
        Instant opens = Instant.parse("2026-11-11T18:00:00Z");
        assertFalse(FrontierSchedule.endOpen(opens.minusSeconds(1), opens));
        assertTrue(FrontierSchedule.endOpen(opens, opens));
        assertFalse(FrontierSchedule.endOpen(opens, null), "no End scheduled");
        assertEquals(Duration.ZERO, FrontierSchedule.until(opens.plusSeconds(5), opens));
    }

    @Test
    void wildlandsAreTheOuterRing() {
        WildlandsZone zone = new WildlandsZone(0, 0, 2000);
        assertFalse(zone.contains(0, 0));
        assertFalse(zone.contains(1999.9, -1999.9), "just inside the safe centre");
        assertTrue(zone.contains(2000, 0));
        assertTrue(zone.contains(-500, -2400), "square, like the border");
        assertEquals(2400, zone.distance(-500, -2400));
        assertEquals(1936, zone.safeRadius(3000, 64), "landings stay clear of the edge");
        assertFalse(new WildlandsZone(0, 0, 0).contains(2999, 2999), "0 turns them off");
        assertEquals(2936, new WildlandsZone(0, 0, 0).safeRadius(3000, 64));
    }

    @Test
    void worldKeysComeFromTheId() {
        FrontierRow row = new FrontierRow(7, Modifier.DROUGHT, 42L, Optional.of("chapter-1"),
                FrontierRow.State.OPEN, Instant.EPOCH, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty());
        assertEquals("rapidskies:frontier_7", row.worldKey());
        assertEquals("rapidskies:frontier_7_nether", row.netherKey());
        assertEquals("rapidskies:frontier_7_end", row.endKey());
        assertTrue(row.owns("rapidskies:frontier_7_end"));
        assertFalse(row.owns("rapidskies:frontier_70"));
        assertEquals(7, FrontierRow.idOf("rapidskies:frontier_7_nether"));
        assertEquals(-1, FrontierRow.idOf("minecraft:overworld"));
        assertTrue(FrontierRow.isFrontierWorld("rapidskies:frontier_12"));
        assertFalse(FrontierRow.isFrontierWorld("rapidskies:spawn"));
    }

    @Test
    void modifierIdsParse() {
        assertEquals(Optional.of(Modifier.OVERGROWTH), Modifier.parse(" Overgrowth "));
        assertEquals(Optional.of(Modifier.DROUGHT), Modifier.parse("drought"));
        assertEquals(Optional.of(Modifier.SHATTERED_SKIES), Modifier.parse("shattered-skies"), "the config's spelling");
        assertEquals(Optional.of(Modifier.SHATTERED_SKIES), Modifier.parse("shattered_skies"), "the generator id's");
        assertEquals(Optional.of(Modifier.DEEP_DARK), Modifier.parse("Deep-Dark"));
        assertEquals(Optional.empty(), Modifier.parse("lava-lakes"));
        assertEquals(Optional.empty(), Modifier.parse(null));
        assertEquals("shattered-skies", Modifier.SHATTERED_SKIES.configName());
        assertEquals("deep_dark", Modifier.DEEP_DARK.id(), "lang keys, the database and the generator id");
    }

    // ------------------------------------------------------------------ biomes

    /** Biomes that exist in 1.20 (protocol 763): newer ones would show as substitutes on older clients. */
    private static final Set<String> BIOMES_1_20 = Set.of("badlands", "bamboo_jungle", "beach", "birch_forest",
            "cherry_grove", "cold_ocean", "dark_forest", "deep_cold_ocean", "deep_dark", "deep_frozen_ocean",
            "deep_lukewarm_ocean", "deep_ocean", "desert", "dripstone_caves", "eroded_badlands", "flower_forest",
            "forest", "frozen_ocean", "frozen_peaks", "frozen_river", "grove", "ice_spikes", "jagged_peaks", "jungle",
            "lukewarm_ocean", "lush_caves", "mangrove_swamp", "meadow", "mushroom_fields", "ocean",
            "old_growth_birch_forest", "old_growth_pine_taiga", "old_growth_spruce_taiga", "plains", "river",
            "savanna", "savanna_plateau", "snowy_beach", "snowy_plains", "snowy_slopes", "snowy_taiga",
            "sparse_jungle", "stony_peaks", "stony_shore", "sunflower_plains", "swamp", "taiga", "warm_ocean",
            "windswept_forest", "windswept_gravelly_hills", "windswept_hills", "windswept_savanna",
            "wooded_badlands");

    @Test
    void everyBiomeExistsIn120() {
        for (Modifier m : Modifier.values()) {
            for (String key : ModifierBiomes.all(m)) {
                assertTrue(key.startsWith("minecraft:"), key);
                assertTrue(BIOMES_1_20.contains(key.substring("minecraft:".length())), m + ": " + key);
            }
        }
    }

    /**
     * The provider must declare every biome it can return (Paper sorts each biome's features
     * from that list): sweep the whole climate space and check {@link ModifierBiomes#all} covers it.
     */
    @Test
    void everyBiomeAModifierCanPickIsDeclared() {
        for (Modifier m : Modifier.values()) {
            Set<String> declared = Set.copyOf(ModifierBiomes.all(m));
            for (double t = -1; t <= 1.001; t += 0.1) {
                for (double h = -1; h <= 1.001; h += 0.1) {
                    for (double w = -1; w <= 1.001; w += 0.2) {
                        if (m == Modifier.SHATTERED_SKIES) {
                            for (int y = -64; y < 320; y += 8) {
                                String b = ModifierBiomes.skies(t, h, w, y, 24);
                                assertTrue(declared.contains(b), m + " at y " + y + ": " + b);
                            }
                            continue;
                        }
                        for (double c = -1.3; c <= 1.001; c += 0.1) {
                            for (double e = -1; e <= 1.001; e += 0.2) {
                                for (double d = 0; d <= 2; d += 0.25) {
                                    String b = ModifierBiomes.biome(m, t, h, c, e, w, d);
                                    assertTrue(declared.contains(b), m + ": " + b);
                                }
                            }
                        }
                    }
                }
            }
        }
        // FrontierBiomes' fallbacks, for lookups without climate.
        assertTrue(ModifierBiomes.all(Modifier.OVERGROWTH).contains("minecraft:forest"));
        assertTrue(ModifierBiomes.all(Modifier.DROUGHT).contains("minecraft:desert"));
        assertTrue(ModifierBiomes.all(Modifier.SHATTERED_SKIES).contains("minecraft:meadow"));
        assertTrue(ModifierBiomes.all(Modifier.DEEP_DARK).contains("minecraft:plains"));
    }

    @Test
    void overgrowthLandIsForest() {
        Set<String> forests = Set.of("minecraft:dark_forest", "minecraft:jungle", "minecraft:bamboo_jungle",
                "minecraft:sparse_jungle", "minecraft:old_growth_birch_forest", "minecraft:birch_forest",
                "minecraft:forest", "minecraft:flower_forest", "minecraft:cherry_grove", "minecraft:swamp",
                "minecraft:old_growth_spruce_taiga", "minecraft:old_growth_pine_taiga", "minecraft:snowy_taiga");
        for (double t = -1; t <= 1; t += 0.1) {
            for (double h = -1; h <= 1; h += 0.1) {
                String b = ModifierBiomes.biome(Modifier.OVERGROWTH, t, h, 0.4, 0.1, 0.4, 0);
                assertTrue(forests.contains(b), "inland at t=" + t + " h=" + h + ": " + b);
            }
        }
    }

    @Test
    void droughtLandIsDry() {
        for (double t = -1; t <= 1; t += 0.1) {
            for (double h = -1; h <= 1; h += 0.1) {
                for (double e = -1; e <= 1; e += 0.25) {
                    String b = ModifierBiomes.biome(Modifier.DROUGHT, t, h, 0.4, e, 0.4, 0);
                    assertTrue(b.contains("desert") || b.contains("badlands") || b.contains("savanna"), b);
                }
            }
        }
        assertEquals("minecraft:desert", ModifierBiomes.biome(Modifier.DROUGHT, 0.8, -0.8, 0.4, 0, 0.01, 0),
                "dry river valleys");
        assertEquals("minecraft:dripstone_caves", ModifierBiomes.biome(Modifier.DROUGHT, 0, 0, 0.4, 0, 0.4, 0.5));
    }

    @Test
    void oceansRiversAndTheDeepDarkStayWhereVanillaPutsThem() {
        assertEquals("minecraft:deep_lukewarm_ocean", ModifierBiomes.biome(Modifier.OVERGROWTH, 0, 0, -0.6, 0, 0.4, 0));
        assertEquals("minecraft:lukewarm_ocean", ModifierBiomes.biome(Modifier.OVERGROWTH, 0, 0, -0.3, 0, 0.4, 0));
        assertEquals("minecraft:river", ModifierBiomes.biome(Modifier.OVERGROWTH, 0, 0, 0.4, 0, 0.02, 0));
        assertEquals("minecraft:deep_dark", ModifierBiomes.biome(Modifier.OVERGROWTH, 0, 0, 0.4, -0.5, 0.4, 1.3),
                "ancient cities still generate");
        assertEquals("minecraft:deep_dark", ModifierBiomes.biome(Modifier.DROUGHT, 0, 0, 0.4, -0.5, 0.4, 1.3));
        assertEquals("minecraft:lush_caves", ModifierBiomes.biome(Modifier.OVERGROWTH, 0, 0, 0.4, 0, 0.4, 0.5));
        assertEquals("minecraft:beach", ModifierBiomes.biome(Modifier.DROUGHT, 0, 0, -0.15, 0, 0.4, 0));
    }

    @Test
    void droughtSeasHaveNoMonuments() {
        // Its seas are drained: a monument's water would spill over the dry seabed.
        for (String b : ModifierBiomes.all(Modifier.DROUGHT)) {
            assertFalse(b.contains("deep_") && b.endsWith("ocean"), b);
        }
        assertEquals("minecraft:warm_ocean", ModifierBiomes.biome(Modifier.DROUGHT, 0, 0, -0.6, 0, 0.4, 0));
    }

    @Test
    void shatteredSkiesIslandsFloatOverTheSea() {
        Set<String> islands = Set.of("minecraft:snowy_plains", "minecraft:grove", "minecraft:taiga",
                "minecraft:old_growth_pine_taiga", "minecraft:meadow", "minecraft:plains", "minecraft:flower_forest",
                "minecraft:birch_forest", "minecraft:forest", "minecraft:sunflower_plains", "minecraft:cherry_grove",
                "minecraft:savanna", "minecraft:sparse_jungle");
        for (double t = -1; t <= 1; t += 0.1) {
            for (double h = -1; h <= 1; h += 0.1) {
                String high = ModifierBiomes.skies(t, h, 0.3, 150, 24);
                assertTrue(islands.contains(high), "island at t=" + t + " h=" + h + ": " + high);
                String low = ModifierBiomes.skies(t, h, 0.3, 10, 24);
                assertTrue(low.endsWith("ocean") && !low.contains("deep"), "the sea: " + low);
            }
        }
        assertTrue(ModifierBiomes.skies(0.8, 0, 0, 24 + ModifierBiomes.SKY_SEA_MARGIN - 1, 24).endsWith("ocean"),
                "just above the waves is still sea");
        assertEquals("minecraft:warm_ocean", ModifierBiomes.skies(0.8, 0, 0, 0, 24), "pufferfish for Chapter goals");
        assertTrue(ModifierBiomes.all(Modifier.SHATTERED_SKIES).contains("minecraft:cold_ocean"));
    }

    @Test
    void deepDarkIsBarrenAboveAndDarkBelow() {
        Set<String> barren = Set.of("minecraft:snowy_plains", "minecraft:windswept_gravelly_hills",
                "minecraft:plains", "minecraft:savanna", "minecraft:windswept_savanna");
        for (double t = -1; t <= 1; t += 0.1) {
            for (double h = -1; h <= 1; h += 0.1) {
                String b = ModifierBiomes.biome(Modifier.DEEP_DARK, t, h, 0.4, 0.1, 0.4, 0);
                assertTrue(barren.contains(b), "surface at t=" + t + " h=" + h + ": " + b);
            }
        }
        assertEquals("minecraft:deep_dark", ModifierBiomes.biome(Modifier.DEEP_DARK, 0, 0, 0.4, 0.5, 0.4, 0.9),
                "the deep dark spreads beyond vanilla's eroded basins");
        assertEquals("minecraft:lush_caves", ModifierBiomes.biome(Modifier.DEEP_DARK, 0, 0.6, 0.4, 0.5, 0.4, 0.9),
                "with lush pockets where it's wet");
        assertEquals("minecraft:dripstone_caves", ModifierBiomes.biome(Modifier.DEEP_DARK, 0, 0, 0.4, 0, 0.4, 0.4));
        assertEquals("minecraft:stony_shore", ModifierBiomes.biome(Modifier.DEEP_DARK, 0, 0, -0.15, 0, 0.4, 0));
    }
}
