package us.rapidskies.core.cosmetic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import us.rapidskies.common.config.ConfigView;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.common.rank.Rank;
import us.rapidskies.common.shop.ShopRepository;
import us.rapidskies.core.cosmetic.Cosmetic.Obtain;

/** The bundled catalog loads, and what it sells follows PLAN.md §6.1, §6.2 and §8. */
class CatalogTest {

    private static final Catalog CATALOG = load();

    private static Catalog load() {
        try {
            return Catalog.load(CatalogTest.class.getClassLoader());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static ConfigView yaml(String text) {
        return ConfigView.load(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void bundledCatalogHasEveryType() {
        for (CosmeticType t : CosmeticType.values()) {
            assertFalse(CATALOG.ofType(t).isEmpty(), "no " + t.id() + " cosmetics");
        }
    }

    @Test
    void displaysRenderAsText() {
        for (Cosmetic c : CATALOG.all()) {
            if (c.type() == CosmeticType.TITLE || c.type() == CosmeticType.TAG || c.type() == CosmeticType.BADGE) {
                String plain = PlainTextComponentSerializer.plainText().serialize(Lang.parse(c.display()));
                assertFalse(plain.isBlank(), c.id());
                assertFalse(plain.contains("<"), c.id() + " has a tag MiniMessage didn't parse: " + plain);
            }
        }
    }

    @Test
    void everyRankHasItsSet() {
        for (Rank r : Rank.values()) {
            if (r == Rank.DEFAULT) {
                continue;
            }
            assertTrue(CATALOG.all().stream().anyMatch(c -> c.obtain() == Obtain.RANK && c.rank() == r),
                    "no rank-set cosmetic for " + r.group());
            assertTrue(CATALOG.rankPasses().containsKey(r), "no Bolts pass for " + r.group());
        }
    }

    @Test
    void nameColorsIncludeTheReds() {
        Set<NamedTextColor> colors = new HashSet<>();
        CATALOG.ofType(CosmeticType.NAME_COLOR).forEach(c -> colors.add(c.nameColor()));
        assertTrue(colors.contains(NamedTextColor.RED));
        assertTrue(colors.contains(NamedTextColor.DARK_RED));
        assertThrows(IllegalArgumentException.class, () -> Catalog.parse(yaml("""
                cosmetics:
                  namecolor:
                    rainbow: { name: Rainbow, display: rainbow, obtain: shop, price: 10 }
                """)));
    }

    @Test
    void numberedCosmeticsFillIn() {
        Cosmetic champion = CATALOG.find("title:season-3-champion").orElseThrow();
        assertEquals("Season 3 Champion", champion.name());
        assertTrue(champion.display().contains("Season 3 Champion"));
        assertTrue(CATALOG.find("badge:season-12-top10").isPresent());
        assertFalse(CATALOG.find("title:season-x-champion").isPresent());
        // What rapid-horizons and rapid-skyrealms grant must exist.
        assertTrue(CATALOG.exists("title:pathfinder"));
        assertTrue(CATALOG.exists("title:season-1-podium"));
    }

    @Test
    void featuredPoolCanRotateWithoutRepeats() {
        // FeaturedRotation only promises no week-to-week repeats with 3× the shown items.
        assertTrue(CATALOG.featuredPool().size() >= 3 * CATALOG.featuredSize(),
                "the featured pool needs at least " + 3 * CATALOG.featuredSize() + " items");
        Instant now = Instant.parse("2026-11-11T12:00:00Z");
        List<Cosmetic> thisWeek = CATALOG.featured(now);
        assertEquals(CATALOG.featuredSize(), thisWeek.size());
        Set<Cosmetic> nextWeek = new HashSet<>(CATALOG.featured(now.plusSeconds(7 * 86400)));
        thisWeek.forEach(c -> assertFalse(nextWeek.contains(c), c.id() + " featured two weeks running"));
        for (Cosmetic c : thisWeek) {
            assertTrue(CATALOG.onSale(c, now));
        }
    }

    /** PLAN.md §8: the shop only sells things tagged COSMETIC or CONVENIENCE. */
    @Test
    void shopSellsOnlyCosmeticsAndConvenience() {
        for (Cosmetic c : CATALOG.all()) {
            if (c.obtain().bought()) {
                assertTrue(c.price() > 0, c.id() + " is sold without a price");
            } else {
                assertEquals(0, c.price(), c.id() + " has a price but isn't sold");
                assertFalse(CATALOG.onSale(c, Instant.now()), c.id() + " is earned, not sold");
            }
        }
        for (Catalog.RankPass pass : CATALOG.rankPasses().values()) {
            assertEquals(ShopRepository.Tag.CONVENIENCE, pass.tag());
            assertEquals(30, pass.days());
        }
        assertThrows(IllegalArgumentException.class, () -> ShopRepository.Tag.parse("ITEM"));
        assertThrows(IllegalArgumentException.class, () -> ShopRepository.Tag.parse("GAMEPLAY"));
        assertThrows(IllegalArgumentException.class, () -> Catalog.parse(yaml("""
                rank-passes:
                  rapidplus: { price: 10, days: 30, tag: boost }
                """)));
    }

    @Test
    void refusesBrokenEntries() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Catalog.parse(yaml("""
                cosmetics:
                  trail:
                    bad: { name: Bad, display: NOT_A_PARTICLE, obtain: shop, price: 10 }
                    needs-data: { name: Block, display: BLOCK, obtain: shop, price: 10 }
                    free: { name: Free, display: HEART, obtain: shop }
                  title:
                    earned: { name: Earned, display: "x", obtain: level, price: 5 }
                    ranked: { name: Ranked, display: "x", obtain: rank }
                  hat:
                    nope: { name: Hat, display: "x", obtain: shop, price: 1 }
                """)));
        for (String id : List.of("trail:bad", "trail:needs-data", "trail:free", "title:earned", "title:ranked",
                "cosmetics.hat")) {
            assertTrue(e.getMessage().contains(id), "no problem reported for " + id + ":\n" + e.getMessage());
        }
    }

    @Test
    void dustTakesAColor() {
        Cosmetic.Effect dust = Catalog.effect("dust #FFD800");
        assertEquals(0xFFD800, dust.dust().asRGB());
        assertThrows(IllegalArgumentException.class, () -> Catalog.effect("dust"));
        assertThrows(IllegalArgumentException.class, () -> Catalog.effect("dust yellow"));
    }
}
