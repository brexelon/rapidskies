package us.rapidskies.common.tailwind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import us.rapidskies.common.tailwind.TailwindBank.Rules;

class TailwindRulesTest {

    private static final long HOUR = 3600;
    private static final long DAY = 24 * HOUR;
    /** 2 hours of Tailwind per day away, at most 3 days counted. */
    private final Rules rules = new Rules(Duration.ofHours(2), 3);

    @Test
    void accruesInProportionToTimeAway() {
        assertEquals(2 * HOUR, rules.accrue(0, DAY));
        assertEquals(HOUR, rules.accrue(0, DAY / 2));
        assertEquals(5 * 60, rules.accrue(0, HOUR), "an hour away is worth 5 minutes");
    }

    @Test
    void stopsAtThreeDays() {
        assertEquals(6 * HOUR, rules.capSeconds());
        assertEquals(6 * HOUR, rules.accrue(0, 30 * DAY));
        assertEquals(6 * HOUR, rules.accrue(5 * HOUR, DAY));
    }

    @Test
    void neverLowersABankAboveTheCap() {
        assertEquals(10 * HOUR, rules.accrue(10 * HOUR, DAY));
    }

    @Test
    void nothingForNoTime() {
        assertEquals(42, rules.accrue(42, 0));
        assertEquals(42, rules.accrue(42, -5), "clock skew can't take Tailwind away");
    }

    @Test
    void rejectsNegativeRules() {
        assertThrows(IllegalArgumentException.class, () -> new Rules(Duration.ofHours(-1), 3));
        assertThrows(IllegalArgumentException.class, () -> new Rules(Duration.ofHours(1), -1));
    }
}
