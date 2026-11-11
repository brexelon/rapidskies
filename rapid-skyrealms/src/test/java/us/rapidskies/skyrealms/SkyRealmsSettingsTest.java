package us.rapidskies.skyrealms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import us.rapidskies.common.config.ConfigView;
import us.rapidskies.skyrealms.expedition.Theme;

class SkyRealmsSettingsTest {

    private static SkyRealmsSettings bundled() throws IOException {
        try (InputStream in = SkyRealmsSettingsTest.class.getClassLoader().getResourceAsStream("config.yml")) {
            return SkyRealmsSettings.from(ConfigView.load(in));
        }
    }

    @Test
    void bundledConfigLoads() throws IOException {
        SkyRealmsSettings s = bundled();
        assertEquals(List.of("meadow", "desert", "frost"), s.tiers().stream().map(SkyRealmsSettings.Tier::id).toList(),
                "Realm Tiers 1-3 in order (PLAN.md phase 4b)");
        assertTrue(s.firstTier().requires().isEmpty());
        assertEquals("desert", s.next(s.firstTier()).orElseThrow().id());
        assertTrue(s.next(s.tiers().getLast()).isEmpty());
        assertEquals(List.of(Theme.values()), s.expeditions().themes(), "all three themes rotate");
        assertEquals(List.of("meadow", "cherry", "atoll"), s.islands().layouts());
        assertFalse(s.shop().categories().isEmpty());
        assertEquals(Map.of("daily", 24, "weekly", 168), s.routes().intervals());
    }

    @Test
    void everyTierAddsResources() throws IOException {
        SkyRealmsSettings s = bundled();
        for (int i = 1; i < s.tiers().size(); i++) {
            var below = s.tiers().get(i - 1).generator().keySet();
            var here = s.tiers().get(i).generator().keySet();
            assertTrue(here.containsAll(below), s.tiers().get(i).id() + " keeps what the tier below gives");
            assertTrue(here.size() > below.size(), s.tiers().get(i).id() + " adds a new resource");
            assertTrue(s.tiers().get(i).cost() > s.tiers().get(i - 1).cost());
        }
    }

    @Test
    void everyShopCategoryOpensOnARealTier() throws IOException {
        SkyRealmsSettings s = bundled();
        for (SkyRealmsSettings.Category c : s.shop().categories()) {
            assertTrue(s.tier(c.tier()).isPresent(), c.id());
        }
        // Desert and frost goods are gated by their tiers.
        assertEquals("desert", s.shop().categories().stream().filter(c -> c.id().equals("desert")).findFirst()
                .orElseThrow().tier());
    }

    @Test
    void awardsGoToTheBestRankReached() throws IOException {
        SkyRealmsSettings.Seasons seasons = bundled().seasons();
        assertEquals("title:season-4-champion", seasons.award(1).orElseThrow().cosmeticFor(4));
        assertEquals("title:season-4-podium", seasons.award(3).orElseThrow().cosmeticFor(4));
        assertEquals(100, seasons.award(10).orElseThrow().bolts());
        assertTrue(seasons.award(11).isEmpty());
    }

    @Test
    void itemAmounts() {
        assertEquals(new SkyRealmsSettings.ItemAmount(Material.BREAD, 8, 8), SkyRealmsSettings.ItemAmount.parse("bread x8"));
        assertEquals(new SkyRealmsSettings.ItemAmount(Material.RAW_IRON, 1, 2),
                SkyRealmsSettings.ItemAmount.parse("minecraft:raw_iron x1-2"));
        assertEquals(new SkyRealmsSettings.ItemAmount(Material.CACTUS, 1, 1), SkyRealmsSettings.ItemAmount.parse("cactus"));
        assertThrows(IllegalArgumentException.class, () -> SkyRealmsSettings.ItemAmount.parse("notathing x2"));
        assertThrows(IllegalArgumentException.class, () -> SkyRealmsSettings.ItemAmount.parse("bread 8"));
        assertThrows(IllegalArgumentException.class, () -> SkyRealmsSettings.ItemAmount.parse("bread x3-1"));
    }

    @Test
    void shopNeverPaysMoreThanItCosts() {
        assertThrows(IllegalArgumentException.class, () -> SkyRealmsSettings.shopItem("sand 2 3"));
        assertThrows(IllegalArgumentException.class, () -> SkyRealmsSettings.shopItem("sand 0 0"));
        assertThrows(IllegalArgumentException.class, () -> SkyRealmsSettings.shopItem("sand -1 0"));
        SkyRealmsSettings.ShopItem cobble = SkyRealmsSettings.shopItem("cobblestone 0 0.5");
        assertFalse(cobble.buyable());
        assertTrue(cobble.sellable());
    }

    @Test
    void problemsAreReportedTogether() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("islands", Map.of("layouts", List.of("castle")));
        root.put("tiers", Map.of("meadow", Map.of("requires", List.of("level 5"), "generator", Map.of())));
        root.put("expeditions", Map.of("themes", List.of("volcano")));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SkyRealmsSettings.from(ConfigView.of(root)));
        String msg = e.getMessage();
        assertTrue(msg.contains("unknown layout 'castle'"), msg);
        assertTrue(msg.contains("first tier can't have requirements"), msg);
        assertTrue(msg.contains("tiers.meadow.generator"), msg);
        assertTrue(msg.contains("unknown theme 'volcano'"), msg);
    }

    @Test
    void bundledNpcsWaitForTheProfile() throws IOException {
        SkyRealmsSettings s = bundled();
        assertEquals(List.of("island", "shop", "realm", "expedition", "route", "hub"),
                s.npcs().stream().map(SkyRealmsSettings.Npc::id).toList());
        assertTrue(s.npcs().stream().allMatch(n -> n.location().isEmpty()), "the skyrealms profile places them");
        assertEquals(new SkyRealmsSettings.NpcAction(SkyRealmsSettings.NpcAction.Kind.SERVER, "hub"),
                s.npcs().getLast().action());
    }

    @Test
    void npcActionsParse() {
        assertEquals(new SkyRealmsSettings.NpcAction(SkyRealmsSettings.NpcAction.Kind.COMMAND, "is"),
                SkyRealmsSettings.NpcAction.parse("command: /is"));
        assertEquals(new SkyRealmsSettings.NpcAction(SkyRealmsSettings.NpcAction.Kind.SERVER, "hub"),
                SkyRealmsSettings.NpcAction.parse(" Server:hub "));
        assertThrows(IllegalArgumentException.class, () -> SkyRealmsSettings.NpcAction.parse("shop"));
        assertThrows(IllegalArgumentException.class, () -> SkyRealmsSettings.NpcAction.parse("command:"));
        assertThrows(IllegalArgumentException.class, () -> SkyRealmsSettings.NpcAction.parse("teleport:spawn"));
    }

    @Test
    void npcsParseFromTheProfileFormat() throws IOException {
        Map<String, Object> root;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            root = new LinkedHashMap<>(ConfigView.load(in).asMap());
        }
        root.put("npcs", Map.of(
                "shop", Map.of("location", "minecraft:overworld, 22.5, 150, -25.5, -124, 0", "action", "command:shop",
                        "skin", "Notch"),
                "Bad Id", Map.of("action", "command:shop"),
                "odd", Map.of("action", "fly", "skin-texture", "abc")));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SkyRealmsSettings.from(ConfigView.of(root)));
        assertTrue(e.getMessage().contains("npcs.Bad Id"), e.getMessage());
        assertTrue(e.getMessage().contains("npcs.odd.action"), e.getMessage());

        root.put("npcs", Map.of("shop", Map.of("location", "minecraft:overworld, 22.5, 150, -25.5, -124, 0",
                "action", "command:shop", "skin", "Notch")));
        SkyRealmsSettings.Npc npc = SkyRealmsSettings.from(ConfigView.of(root)).npcs().getFirst();
        assertEquals("minecraft:overworld", npc.location().orElseThrow().world());
        assertEquals(-25.5, npc.location().orElseThrow().z());
        assertEquals(-124f, npc.location().orElseThrow().yaw());
        assertEquals("Notch", npc.skin().name());
        assertFalse(npc.skin().fixed());
    }

    @Test
    void routeNumbers() {
        assertEquals(2000, Commands.number("2,000"));
        assertEquals(2500, Commands.number("2.5k"));
        assertEquals(1_000_000, Commands.number("1m"));
        assertEquals(-1, Commands.number("1.5"));
        assertEquals(-1, Commands.number("-5"));
        assertEquals(-1, Commands.number("lots"));
    }
}
