package us.rapidskies.skyrealms.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import us.rapidskies.skyrealms.SkyRealmsSettings;

class ShopPricingTest {

    private static final SkyRealmsSettings.Shop SHOP = new SkyRealmsSettings.Shop(1000, 50, 25, List.of());

    @Test
    void fullPriceUntilTheThreshold() {
        assertEquals(500, ShopPricing.capped(0, 500, SHOP), 1e-9);
        assertEquals(1000, ShopPricing.capped(0, 1000, SHOP), 1e-9);
    }

    @Test
    void reducedThenFloor() {
        // 200 at full, 300 at half.
        assertEquals(200 + 150, ShopPricing.capped(800, 500, SHOP), 1e-9);
        // Straddling all three bands: 1000 full, 1000 at 50%, 1000 at 25%.
        assertEquals(1000 + 500 + 250, ShopPricing.capped(0, 3000, SHOP), 1e-9);
        assertEquals(25, ShopPricing.capped(5000, 100, SHOP), 1e-9);
    }

    @Test
    void tailwindAndRounding() {
        assertEquals(150, ShopPricing.payout(0, 100, SHOP, 1.5));
        assertEquals(0, ShopPricing.payout(0, 0.5, SHOP, 1.0), "half a Coin rounds down");
        assertEquals(100, ShopPricing.payout(0, 100, SHOP, 0.2), "Tailwind never lowers a sale");
    }

    @Test
    void noCapWhenOff() {
        SkyRealmsSettings.Shop off = new SkyRealmsSettings.Shop(0, 50, 25, List.of());
        assertEquals(10_000, ShopPricing.capped(1_000_000, 10_000, off), 1e-9);
        assertEquals(100, ShopPricing.ratePercent(1_000_000, off));
    }

    @Test
    void rates() {
        assertEquals(100, ShopPricing.ratePercent(999, SHOP));
        assertEquals(50, ShopPricing.ratePercent(1000, SHOP));
        assertEquals(25, ShopPricing.ratePercent(2000, SHOP));
    }

    @Test
    void buyingRoundsUp() {
        assertEquals(32, ShopPricing.cost(0.5, 64));
        assertEquals(1, ShopPricing.cost(0.25, 1));
        assertEquals(768, ShopPricing.cost(12, 64));
    }

    @Test
    void prices() {
        assertEquals("2", ShopMenu.price(2));
        assertEquals("0.50", ShopMenu.price(0.5));
        assertEquals("1,200", ShopMenu.price(1200));
    }
}
