package us.rapidskies.essentials.money;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Typed amounts of Coins and Crowns, which are whole numbers: {@code 250}, {@code 1,500},
 * {@code 2.5k}, {@code 1m}. Anything that isn't a positive whole amount is refused, never
 * rounded, so {@code /pay} never moves a different amount from the one typed.
 */
public final class Money {

    /** Far above any real balance (same ceiling as rapid-core's Vault amounts). */
    public static final long MAX = 1_000_000_000_000L;

    private static final Pattern AMOUNT = Pattern.compile("(\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.(\\d+))?([km]?)");

    private Money() {
    }

    /** The whole, positive amount {@code input} means, or empty. */
    public static OptionalLong parse(String input) {
        if (input == null) {
            return OptionalLong.empty();
        }
        Matcher m = AMOUNT.matcher(input.trim().toLowerCase(Locale.ROOT));
        if (!m.matches()) {
            return OptionalLong.empty();
        }
        String digits = m.group(1).replace(",", "") + (m.group(2) == null ? "" : "." + m.group(2));
        BigDecimal value = new BigDecimal(digits);
        value = switch (m.group(3)) {
            case "k" -> value.scaleByPowerOfTen(3);
            case "m" -> value.scaleByPowerOfTen(6);
            default -> value;
        };
        try {
            long whole = value.stripTrailingZeros().longValueExact();
            return whole > 0 && whole <= MAX ? OptionalLong.of(whole) : OptionalLong.empty();
        } catch (ArithmeticException e) {
            // A fraction of a Coin, or too big for a long.
            return OptionalLong.empty();
        }
    }

    /** {@code 1234567} as {@code 1,234,567}. */
    public static String format(long amount) {
        return NumberFormat.getIntegerInstance(Locale.US).format(amount);
    }
}
