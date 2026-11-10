package us.rapidskies.claims;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import us.rapidskies.claims.model.Flag;
import us.rapidskies.claims.town.Project;
import us.rapidskies.common.config.ConfigView;

class ClaimSettingsTest {

    private static ClaimSettings bundled() throws Exception {
        try (InputStream in = ClaimSettingsTest.class.getClassLoader().getResourceAsStream("config.yml")) {
            return ClaimSettings.from(ConfigView.load(in));
        }
    }

    /** The shipped config matches PLAN.md §4.1's numbers. */
    @Test
    void bundledConfigMatchesThePlan() throws Exception {
        ClaimSettings s = bundled();
        assertEquals(java.util.Set.of("minecraft:overworld"), s.worlds(), "the Heartland only");
        assertEquals(9, s.allowance().starter(), "9 starter chunks");
        assertEquals(5000, s.towns().foundCost(), "founding costs 5,000 Crowns");
        assertEquals(14, s.towns().graceDays());
        assertEquals(7, s.towns().ruinsDays());
        assertEquals(90, s.inactivity().days());
        assertFalse(Flag.PVP.in(s.defaultFlags()), "PvP inside claims defaults off");
        assertTrue(Flag.MOB_SPAWNING.in(s.defaultFlags()));
        Project lighthouse = s.project("lighthouse").orElseThrow();
        assertEquals(Map.of("minecraft:stone_bricks", 2000L, "minecraft:glass", 64L, "crowns", 5000L),
                lighthouse.requires(), "the plan's example Project");
        assertTrue(lighthouse.unlocks(Project.Unlock.Kind.WARP));
        assertTrue(s.projects().stream().anyMatch(p -> p.unlocks(Project.Unlock.Kind.VAULT)));
        assertTrue(s.projects().stream().anyMatch(p -> p.extraChunks() > 0));
    }

    @Test
    void emptyConfigGivesDefaults() {
        ClaimSettings s = ClaimSettings.from(ConfigView.of(Map.of("worlds", List.of("minecraft:overworld"))));
        assertEquals(9, s.allowance().starter());
        assertEquals(Flag.MOB_SPAWNING.bit | Flag.LEAF_DECAY.bit, s.defaultFlags());
        assertTrue(s.projects().isEmpty());
    }

    @Test
    void risingPurchasePriceAndPlaytime() throws Exception {
        ClaimSettings.Allowance a = bundled().allowance();
        assertEquals(500, a.price(0));
        assertEquals(750, a.price(1));
        assertEquals(0, a.fromPlaytime(119));
        assertEquals(1, a.fromPlaytime(120));
        assertEquals(40, a.fromPlaytime(1_000_000), "capped");
    }

    @Test
    void townCapAndUpkeepScale() throws Exception {
        ClaimSettings.Towns t = bundled().towns();
        assertEquals(16 + 8 * 3 + 16, t.chunkCap(3, 16));
        assertEquals(50 + 10 * 20, t.upkeep(20));
    }

    @Test
    void everyProblemIsReported() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ClaimSettings.from(
                ConfigView.of(Map.of(
                        "worlds", List.of(),
                        "allowance", Map.of("starter", -1),
                        "flags", Map.of("defaults", List.of("explosions")),
                        "projects", Map.of(
                                "Bad Id", Map.of("requires", Map.of("glass", 1)),
                                "empty", Map.of("requires", Map.of()),
                                "odd", Map.of("requires", Map.of("glass", 0, "crowns", "lots"),
                                        "unlocks", List.of("teleporter")))))));
        String m = e.getMessage();
        for (String expected : new String[] {"worlds", "allowance.starter", "explosions", "projects.Bad Id",
            "projects.empty.requires", "projects.odd.requires.glass", "projects.odd.requires.crowns", "teleporter"}) {
            assertTrue(m.contains(expected), "should mention " + expected + ": " + m);
        }
    }

    @Test
    void unlockParsing() {
        assertEquals(new Project.Unlock(Project.Unlock.Kind.CHUNKS, 16), Project.Unlock.parse("chunks:16").orElseThrow());
        assertEquals(Project.Unlock.Kind.MARKET_STALL, Project.Unlock.parse("market-stall").orElseThrow().kind());
        assertTrue(Project.Unlock.parse("chunks:0").isEmpty());
        assertTrue(Project.Unlock.parse("chunks:x").isEmpty());
    }
}
