package us.rapidskies.core.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class AmountsTest {

    @Test
    void wholeAmountsPassThrough() {
        assertEquals(OptionalLong.of(250), Amounts.forDeposit(250));
        assertEquals(OptionalLong.of(250), Amounts.forWithdrawal(250));
        assertEquals(OptionalLong.of(0), Amounts.forDeposit(0));
    }

    @Test
    void fractionsRoundAgainstTheReceiver() {
        assertEquals(OptionalLong.of(10), Amounts.forDeposit(10.99), "deposits round down");
        assertEquals(OptionalLong.of(11), Amounts.forWithdrawal(10.01), "withdrawals round up");
        assertEquals(OptionalLong.of(0), Amounts.forDeposit(0.4), "selling at 0.4 each never mints a Crown");
        assertEquals(OptionalLong.of(1), Amounts.forWithdrawal(0.4));
    }

    @Test
    void nonsenseIsRefused() {
        for (double bad : new double[] {-1, -0.5, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Amounts.MAX + 1.0, 1e300}) {
            assertEquals(OptionalLong.empty(), Amounts.forDeposit(bad), "deposit " + bad);
            assertEquals(OptionalLong.empty(), Amounts.forWithdrawal(bad), "withdraw " + bad);
        }
    }
}
