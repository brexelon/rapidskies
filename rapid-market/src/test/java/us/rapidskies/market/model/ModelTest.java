package us.rapidskies.market.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/** The auction house's rules that need no server: categories, fees, prices, names, alerts. */
class ModelTest {

    @Test
    void categoriesPreferTheMostSpecificGroup() {
        assertEquals(Category.TOOLS, Category.classify("minecraft:diamond_pickaxe", false, false));
        assertEquals(Category.TOOLS, Category.classify("netherite_chestplate", false, false));
        assertEquals(Category.TOOLS, Category.classify("bow", false, false));
        assertEquals(Category.TOOLS, Category.classify("diamond_horse_armor", false, false));
        assertEquals(Category.REDSTONE, Category.classify("sticky_piston", true, false));
        assertEquals(Category.REDSTONE, Category.classify("oak_button", true, false));
        assertEquals(Category.REDSTONE, Category.classify("powered_rail", true, false));
        assertEquals(Category.REDSTONE, Category.classify("hopper_minecart", false, false));
        assertEquals(Category.FARMING, Category.classify("wheat_seeds", false, false));
        assertEquals(Category.FARMING, Category.classify("cherry_sapling", true, false));
        assertEquals(Category.FARMING, Category.classify("hay_block", true, false), "a bale is farming, not blocks");
        assertEquals(Category.FARMING, Category.classify("melon", true, false));
        assertEquals(Category.FOOD, Category.classify("cooked_beef", false, true));
        assertEquals(Category.FOOD, Category.classify("carrot", false, true));
        assertEquals(Category.BLOCKS, Category.classify("pale_oak_log", true, false));
        assertEquals(Category.BLOCKS, Category.classify("stone_bricks", true, false));
        assertEquals(Category.MISC, Category.classify("ender_pearl", false, false));
        assertEquals(Category.MISC, Category.classify("enchanted_book", false, false));
    }

    @Test
    void categoriesRoundTripById() {
        for (Category c : Category.values()) {
            assertEquals(c, Category.byId(c.id()).orElseThrow());
        }
        assertTrue(Category.byId("nope").isEmpty());
        assertTrue(Category.byId(null).isEmpty());
    }

    @Test
    void feesRoundInTheEconomysFavour() {
        Fees f = new Fees(new BigDecimal("2"), new BigDecimal("5"));
        assertEquals(1, f.listingFee(10), "2% of 10 is 0.2: at least 1");
        assertEquals(20, f.listingFee(1000));
        assertEquals(21, f.listingFee(1001), "20.02 rounds up");
        assertEquals(50, f.tax(1000));
        assertEquals(950, f.proceeds(1000));
        assertEquals(1, f.tax(10), "0.5 rounds up");
        assertEquals(9, f.proceeds(10));
        assertEquals(0, f.tax(1), "the seller always gets at least 1");
        assertEquals(1, f.proceeds(1));
        assertEquals(50_000_000L, f.tax(1_000_000_000L));
    }

    @Test
    void feesAllowDecimalsAndZero() {
        Fees f = new Fees(new BigDecimal("0"), new BigDecimal("2.5"));
        assertEquals(1, f.listingFee(1_000_000), "never free");
        assertEquals(25, f.tax(1000));
        assertEquals(26, f.tax(1001));
    }

    @Test
    void feesRefuseAHundredPercentOrMore() {
        assertThrows(IllegalArgumentException.class, () -> new Fees(new BigDecimal("100"), BigDecimal.ONE));
        assertThrows(IllegalArgumentException.class, () -> new Fees(BigDecimal.ONE, new BigDecimal("-1")));
    }

    @Test
    void pricesAreWholeAndPositive() {
        assertEquals(OptionalLong.of(250), Prices.parse("250"));
        assertEquals(OptionalLong.of(1500), Prices.parse("1,500"));
        assertEquals(OptionalLong.of(2500), Prices.parse("2.5k"));
        assertEquals(OptionalLong.of(1_000_000), Prices.parse("1M"));
        assertEquals(OptionalLong.empty(), Prices.parse("2.5"), "no fractions of a Coin");
        assertEquals(OptionalLong.empty(), Prices.parse("0"));
        assertEquals(OptionalLong.empty(), Prices.parse("-5"));
        assertEquals(OptionalLong.empty(), Prices.parse("1,50"));
        assertEquals(OptionalLong.empty(), Prices.parse("lots"));
        assertEquals(OptionalLong.empty(), Prices.parse(null));
        assertEquals(OptionalLong.empty(), Prices.parse("99999999999999999999"));
        assertEquals("1,234,567", Prices.format(1_234_567L));
        assertEquals("12.5", Prices.format(12.5));
        assertEquals("12", Prices.format(12.0));
    }

    @Test
    void trueNamesFromKeys() {
        assertEquals("Pale Oak Log", TrueNames.fromKey("minecraft:pale_oak_log"));
        assertEquals("Carrot on a Stick", TrueNames.fromKey("carrot_on_a_stick"));
        assertEquals("Heart of the Sea", TrueNames.fromKey("heart_of_the_sea"));
        assertEquals("Trial Key", TrueNames.fromKey("trial_key"));
        assertTrue(TrueNames.untranslated("item.minecraft.trial_key", "item.minecraft.trial_key"));
        assertTrue(TrueNames.untranslated("block.minecraft.crafter", "x"));
        assertTrue(TrueNames.untranslated("", "x"));
        assertFalse(TrueNames.untranslated("Trial Key", "item.minecraft.trial_key"));
    }

    @Test
    void alertsOnRepeatedFarBelowAverageSales() {
        AlertRule rule = new AlertRule(25, 50, 3, 7);
        OptionalDouble avg = OptionalDouble.of(1000);
        assertTrue(rule.suspicious(64 * 200, 64, avg), "200 each against 1,000");
        assertFalse(rule.suspicious(64 * 250, 64, avg), "exactly a quarter isn't under it");
        assertFalse(rule.suspicious(10, 1, OptionalDouble.empty()), "no history, no judgement");
        assertFalse(rule.suspicious(1, 64, OptionalDouble.of(10)), "cheap items are ignored");
        assertFalse(rule.alert(2));
        assertTrue(rule.alert(3));
        assertFalse(rule.alert(4), "not on every further sale");
        assertTrue(rule.alert(6));
        assertFalse(new AlertRule(25, 50, 0, 7).alert(3), "repeat 0 turns alerts off");
        assertFalse(new AlertRule(0, 50, 3, 7).suspicious(1, 1, avg), "below 0 turns flagging off");
    }

    @Test
    void sortsCycle() {
        Sort s = Sort.NEWEST;
        for (int i = 0; i < Sort.values().length; i++) {
            s = s.next();
        }
        assertEquals(Sort.NEWEST, s);
    }
}
