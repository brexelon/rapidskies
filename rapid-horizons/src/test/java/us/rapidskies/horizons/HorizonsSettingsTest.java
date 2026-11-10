package us.rapidskies.horizons;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import us.rapidskies.common.config.ConfigView;
import us.rapidskies.horizons.HorizonsSettings.NpcAction;
import us.rapidskies.horizons.trade.Trade;
import us.rapidskies.horizons.travel.PortalLayout;

class HorizonsSettingsTest {

    private static HorizonsSettings bundled() {
        try (InputStream in = HorizonsSettingsTest.class.getClassLoader().getResourceAsStream("config.yml")) {
            return HorizonsSettings.from(ConfigView.load(in));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void bundledConfigParses() {
        HorizonsSettings s = bundled();
        assertEquals("minecraft:overworld", s.rtp().world());
        assertTrue(s.rtp().portal().isEmpty(), "no portal until the profile sets one");
        assertTrue(s.rtp().portalBuild());
        assertTrue(s.rtp().portalHologram().isEmpty(), "no hologram until the profile places one");
        assertEquals(List.of("heartland", "trades", "land", "hub"),
                s.npcs().stream().map(HorizonsSettings.Npc::id).toList());
        assertTrue(s.npcs().stream().allMatch(n -> n.location().isEmpty()), "the profile places the NPCs");
        assertEquals(300, s.pvp().toggleCooldownSeconds());
        assertEquals(new HorizonsSettings.CombatLog(true, false, true, List.of()), s.pvp().combatLog(),
                "combat logging kills by default, kicks don't count, no extra commands");
        assertEquals(2, s.trades().rules().maxActive());
        for (Trade t : Trade.values()) {
            assertFalse(s.trades().source(t).xp().isEmpty(), t + " earns from something");
        }
    }

    @Test
    void emptyConfigUsesTheSameDefaults() {
        HorizonsSettings empty = HorizonsSettings.from(ConfigView.empty());
        HorizonsSettings bundled = bundled();
        assertEquals(bundled.rtp(), empty.rtp());
        assertEquals(bundled.pvp(), empty.pvp());
        assertEquals(bundled.trades().rules(), empty.trades().rules());
        for (Trade t : Trade.values()) {
            assertEquals(bundled.trades().source(t), empty.trades().source(t), t.id());
        }
    }

    @Test
    void combatLogCommandsDropSlashesAndBlanks() {
        HorizonsSettings s = HorizonsSettings.from(ConfigView.of(Map.of("pvp", Map.of("combat-log", Map.of(
                "punish-kicks", true, "action-bar", false,
                "commands", List.of("/eco take <player> 100", "  ", "say <player> fled"))))));
        assertEquals(new HorizonsSettings.CombatLog(true, true, false,
                List.of("eco take <player> 100", "say <player> fled")), s.pvp().combatLog());
    }

    @Test
    void deepslateOresPayLikeTheirStoneVariant() {
        HorizonsSettings.Source miner = bundled().trades().source(Trade.MINER);
        assertEquals(12, miner.xpFor("deepslate_diamond_ore"));
        assertEquals(0, miner.xpFor("stone"), "no default for miners");
        assertEquals(2, bundled().trades().source(Trade.HUNTER).xpFor("drowned"), "hunter default");
    }

    @Test
    void portalRegionParsesInAnyCornerOrder() {
        HorizonsSettings.Region r = HorizonsSettings.Region.parse("rapidskies:spawn, 5, 80, -3, -2, 74, 4");
        assertEquals(new HorizonsSettings.Region("rapidskies:spawn", -2, 74, -3, 5, 80, 4), r);
        assertTrue(r.contains("rapidskies:spawn", 0, 75, 0));
        assertFalse(r.contains("rapidskies:spawn", 6, 75, 0));
        assertFalse(r.contains("minecraft:overworld", 0, 75, 0));
    }

    @Test
    void portalHologramParses() {
        HorizonsSettings s = HorizonsSettings.from(hologramAt("rapidskies:spawn, 0.5, 75, 10.5"));
        assertEquals(new us.rapidskies.core.world.SpawnPoint("rapidskies:spawn", 0.5, 75, 10.5, 0, 0),
                s.rtp().portalHologram().orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> HorizonsSettings.from(hologramAt("rapidskies:spawn, 0.5")));
    }

    private static ConfigView hologramAt(String point) {
        Map<String, Object> portal = Map.of("hologram", point);
        Map<String, Object> rtp = Map.of("portal", portal);
        return ConfigView.of(Map.of("rtp", rtp));
    }

    @Test
    void npcActionsParse() {
        assertEquals(new NpcAction(NpcAction.Kind.RTP, ""), NpcAction.parse("rtp"));
        assertEquals(new NpcAction(NpcAction.Kind.TRADES, ""), NpcAction.parse(" Trades "));
        assertEquals(new NpcAction(NpcAction.Kind.SERVER, "hub"), NpcAction.parse("server:hub"));
        assertEquals(new NpcAction(NpcAction.Kind.COMMAND, "claim help"), NpcAction.parse("command: /claim help"));
        assertThrows(IllegalArgumentException.class, () -> NpcAction.parse("server:"));
        assertThrows(IllegalArgumentException.class, () -> NpcAction.parse("teleport:somewhere"));
        assertThrows(IllegalArgumentException.class, () -> NpcAction.parse(""));
    }

    @Test
    void npcsParseFromTheProfileFormat() {
        HorizonsSettings s = HorizonsSettings.from(ConfigView.of(Map.of("npcs", Map.of(
                "trades", Map.of("location", "rapidskies:spawn, -3.5, 74, -3.5, -45, 0", "action", "trades",
                        "skin", "Notch")))));
        HorizonsSettings.Npc npc = s.npcs().getFirst();
        assertEquals("trades", npc.id());
        assertEquals(-3.5, npc.location().orElseThrow().x());
        assertEquals(-45f, npc.location().orElseThrow().yaw());
        assertEquals("Notch", npc.skin().name());
        assertFalse(npc.skin().fixed());
    }

    @Test
    void portalArchFramesTheOpening() {
        // The spawn's arch: a 5-wide, 5-tall opening across the south path, facing the plaza.
        HorizonsSettings.Region opening = HorizonsSettings.Region.parse("rapidskies:spawn, -2, 72, 12, 2, 76, 12");
        List<PortalLayout.Placement> arch = PortalLayout.of(opening).orElseThrow();
        // 25 portal blocks, 2 columns of 5, a lintel of 7, 5 leaves and 2 lanterns on top.
        assertEquals(25 + 10 + 7 + 5 + 2, arch.size());
        assertTrue(arch.stream().allMatch(p -> p.z() == 12), "one block thick");
        assertEquals(25, arch.stream().filter(p -> p.data().equals("minecraft:nether_portal[axis=x]")).count());
        assertTrue(arch.contains(new PortalLayout.Placement(-3, 72, 12, "minecraft:chiseled_stone_bricks")));
        assertTrue(arch.contains(new PortalLayout.Placement(3, 76, 12, "minecraft:mossy_stone_bricks")));
        assertTrue(arch.contains(new PortalLayout.Placement(0, 77, 12, "minecraft:stripped_spruce_log[axis=x]")));
        assertEquals(arch.size(), arch.stream().map(p -> List.of(p.x(), p.y(), p.z())).distinct().count(),
                "no block placed twice");

        List<PortalLayout.Placement> alongZ = PortalLayout.of(
                HorizonsSettings.Region.parse("w:x, 4, 70, 0, 4, 72, 2")).orElseThrow();
        assertTrue(alongZ.stream().allMatch(p -> p.x() == 4));
        assertTrue(alongZ.contains(new PortalLayout.Placement(4, 70, 1, "minecraft:nether_portal[axis=z]")));
        assertTrue(PortalLayout.of(HorizonsSettings.Region.parse("w:x, 0, 70, 0, 2, 72, 2")).isEmpty(),
                "a thick region gets no arch");
    }

    @Test
    void everyProblemIsReportedTogether() {
        ConfigView bad = ConfigView.of(Map.of(
                "rtp", Map.of("min-radius", 500, "max-radius", 100, "portal", Map.of("region", "nope")),
                "pvp", Map.of("combat-tag-seconds", -1),
                "npcs", Map.of("Bad Id", Map.of("action", "rtp"), "ok", Map.of("action", "fly")),
                "trades", Map.of("max-active", 0, "miner", Map.of("xp", Map.of("coal_ore", "lots")))));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> HorizonsSettings.from(bad));
        String msg = e.getMessage();
        assertTrue(msg.contains("rtp.portal.region"), msg);
        assertTrue(msg.contains("rtp.max-radius"), msg);
        assertTrue(msg.contains("pvp.combat-tag-seconds"), msg);
        assertTrue(msg.contains("trades.max-active"), msg);
        assertTrue(msg.contains("trades.miner.xp.coal_ore"), msg);
        assertTrue(msg.contains("npcs.Bad Id"), msg);
        assertTrue(msg.contains("npcs.ok.action"), msg);
    }

    @Test
    void bundledFrontierSkyborneAndChapterOne() {
        HorizonsSettings s = bundled();
        HorizonsSettings.Frontier f = s.frontier();
        assertTrue(f.enabled());
        assertEquals(30, f.lengthDays());
        assertEquals(7, f.warningDays());
        assertEquals(List.of(us.rapidskies.horizons.frontier.Modifier.OVERGROWTH,
                us.rapidskies.horizons.frontier.Modifier.DROUGHT, us.rapidskies.horizons.frontier.Modifier.SHATTERED_SKIES,
                us.rapidskies.horizons.frontier.Modifier.DEEP_DARK), f.modifiers(),
                "all four, the launch two first so a running rotation carries on as it was");
        assertEquals(48, f.drought().waterLevel(), "only the deep sea basins keep water");
        assertTrue(f.shatteredSkies().seaLevel() < 84 - 40, "the sea sits well below the lower islands");
        assertTrue(f.deepDark().cavernTop() - f.deepDark().cavernFloor() >= 16);
        assertTrue(f.deepDark().cavernFloor() > -55, "above vanilla's lava");
        assertEquals(List.of("chapter-1"), f.chapters());
        assertTrue(f.wildlands().innerRadius() < f.borderSize() / 2, "the Wildlands ring fits inside the border");
        assertTrue(f.nether() && f.end().enabled(), "the Nether and End rotate");
        assertEquals(new HorizonsSettings.ItemReward("experience_bottle", 16), f.end().rewards().items().getFirst());

        HorizonsSettings.Skyborne sky = s.skyborne();
        assertTrue(sky.enabled());
        assertEquals(10, sky.announceMinutes(), "announced 10 minutes ahead (PLAN.md §5.2)");
        assertTrue(sky.rewards().bolts() >= 10 && sky.rewards().bolts() <= 20, "10-20 Bolts (PLAN.md §6.2)");

        HorizonsSettings.Chapter one = s.chapter("chapter-1").orElseThrow();
        assertEquals("title:pathfinder", one.cosmetic());
        assertEquals(100, one.bolts(), "Chapter completion is 100 Bolts (PLAN.md §6.2)");
        assertEquals(12, one.entries().size());
        assertTrue(one.entries().stream().anyMatch(e -> e.goal().kind() == us.rapidskies.horizons.chapter.ChapterGoal
                .Kind.SKYBORNE), "event participation is part of it");
        assertTrue(one.entries().stream().anyMatch(e -> e.goal().kind() == us.rapidskies.horizons.chapter.ChapterGoal
                .Kind.MINE), "rare finds are part of it");

        assertEquals(20, s.trades().perks().halfLevel());
        for (Trade t : Trade.values()) {
            assertTrue(s.trades().perks().max().get(t) > 0, t + " has a perk");
        }
    }

    @Test
    void emptyConfigFrontierDefaultsMatchTheBundle() {
        HorizonsSettings empty = HorizonsSettings.from(ConfigView.empty());
        HorizonsSettings bundled = bundled();
        HorizonsSettings.Frontier e = empty.frontier();
        HorizonsSettings.Frontier b = bundled.frontier();
        assertEquals(b.modifiers(), e.modifiers());
        assertEquals(b.wildlands(), e.wildlands());
        assertEquals(b.end().dragonDelayHours(), e.end().dragonDelayHours());
        assertEquals(b.overgrowth(), e.overgrowth());
        assertEquals(b.drought(), e.drought());
        assertEquals(b.shatteredSkies(), e.shatteredSkies());
        assertEquals(b.deepDark(), e.deepDark());
        assertEquals(b.borderSize(), e.borderSize());
        assertEquals(bundled.skyborne().scaling(), empty.skyborne().scaling());
        assertEquals(bundled.skyborne().waveMobs(), empty.skyborne().waveMobs());
        assertEquals(bundled.trades().perks(), empty.trades().perks());
        assertTrue(e.chapters().isEmpty() && empty.chapters().isEmpty(), "no chapters unless configured");
    }

    @Test
    void frontierProblemsAreReported() {
        ConfigView bad = ConfigView.of(Map.of(
                "frontier", Map.of("modifiers", List.of("overgrowth", "lava-lakes"), "chapters", List.of("chapter-9"),
                        "border-size", 3000, "wildlands", Map.of("inner-radius", 1600),
                        "drought", Map.of("water-level", 70),
                        "shattered-skies", Map.of("island-density", 90),
                        "deep-dark", Map.of("cavern-floor", -20, "cavern-top", -10)),
                "skyborne", Map.of("min-height", 20, "rewards", Map.of("items", List.of("diamond lots"))),
                "trades", Map.of("perks", Map.of("miner", 140)),
                "chapters", Map.of("chapter-2", Map.of("entries", Map.of(
                        "a", Map.of("goal", "visit:moon"),
                        "b", Map.of("goal", "distance:500", "amount", 3),
                        "c", Map.of("goal", "mine:diamond_ore", "amount", 0))))));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> HorizonsSettings.from(bad));
        String msg = e.getMessage();
        assertTrue(msg.contains("lava-lakes"), msg);
        assertTrue(msg.contains("shattered-skies, deep-dark"), "lists the modifiers there are: " + msg);
        assertTrue(msg.contains("frontier.drought.water-level"), msg);
        assertTrue(msg.contains("frontier.shattered-skies.island-density"), msg);
        assertTrue(msg.contains("frontier.deep-dark.cavern-top"), msg);
        assertTrue(msg.contains("no chapter 'chapter-9'"), msg);
        assertTrue(msg.contains("frontier.wildlands.inner-radius"), msg);
        assertTrue(msg.contains("skyborne.min-height"), msg);
        assertTrue(msg.contains("skyborne.rewards.items"), msg);
        assertTrue(msg.contains("trades.perks.miner"), msg);
        assertTrue(msg.contains("chapters.chapter-2.entries.a.goal"), msg);
        assertTrue(msg.contains("chapters.chapter-2.entries.b.amount"), msg);
        assertTrue(msg.contains("chapters.chapter-2.entries.c.amount"), msg);
    }

    @Test
    void itemRewardsParse() {
        assertEquals(new HorizonsSettings.ItemReward("diamond", 2), HorizonsSettings.ItemReward.parse(" Minecraft:Diamond 2"));
        assertEquals(new HorizonsSettings.ItemReward("golden_apple", 1), HorizonsSettings.ItemReward.parse("golden_apple"));
        assertThrows(IllegalArgumentException.class, () -> HorizonsSettings.ItemReward.parse("diamond 65"));
        assertThrows(IllegalArgumentException.class, () -> HorizonsSettings.ItemReward.parse("diamond 2 extra"));
    }
}
