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
}
