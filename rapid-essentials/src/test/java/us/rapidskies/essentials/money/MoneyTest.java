package us.rapidskies.essentials.money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void wholeAmounts() {
        assertEquals(OptionalLong.of(250), Money.parse("250"));
        assertEquals(OptionalLong.of(1500), Money.parse("1,500"));
        assertEquals(OptionalLong.of(1_234_567), Money.parse("1,234,567"));
        assertEquals(OptionalLong.of(2500), Money.parse("2.5k"));
        assertEquals(OptionalLong.of(2500), Money.parse("2.5K"));
        assertEquals(OptionalLong.of(1_000_000), Money.parse("1m"));
        assertEquals(OptionalLong.of(7), Money.parse(" 7 "));
        assertEquals(OptionalLong.of(3), Money.parse("3.0"));
    }

    @Test
    void refusedNeverRounded() {
        for (String bad : new String[] {"", "0", "-5", "1.5", "0.0001k", "abc", "1,50", "12,3456", "1e3", "5kk",
                "NaN", "1000000000001", "99999999999999999999"}) {
            assertTrue(Money.parse(bad).isEmpty(), bad);
        }
        assertTrue(Money.parse(null).isEmpty());
    }

    @Test
    void format() {
        assertEquals("0", Money.format(0));
        assertEquals("999", Money.format(999));
        assertEquals("1,250", Money.format(1250));
        assertEquals("1,000,000", Money.format(1_000_000));
    }
}
