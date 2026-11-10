package us.rapidskies.market.model;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Typed prices, which are whole Coins or Crowns: {@code 250}, {@code 1,500}, {@code 2.5k},
 * {@code 1m}. Anything else is refused, never rounded, so a listing never goes up at a
 * different price from the one typed (same rules as rapid-essentials' {@code /pay}).
 */
public final class Prices {

    private static final Pattern AMOUNT = Pattern.compile("(\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.(\\d+))?([km]?)");

    private Prices() {
    }

    public static OptionalLong parse(String input) {
        if (input == null) {
            return OptionalLong.empty();
        }
        Matcher m = AMOUNT.matcher(input.trim().toLowerCase(Locale.ROOT));
        if (!m.matches()) {
            return OptionalLong.empty();
        }
        BigDecimal value = new BigDecimal(m.group(1).replace(",", "") + (m.group(2) == null ? "" : "." + m.group(2)));
        value = switch (m.group(3)) {
            case "k" -> value.scaleByPowerOfTen(3);
            case "m" -> value.scaleByPowerOfTen(6);
            default -> value;
        };
        try {
            long whole = value.stripTrailingZeros().longValueExact();
            return whole > 0 ? OptionalLong.of(whole) : OptionalLong.empty();
        } catch (ArithmeticException e) {
            return OptionalLong.empty();
        }
    }

    /** {@code 1234567} as {@code 1,234,567}. */
    public static String format(long amount) {
        return NumberFormat.getIntegerInstance(Locale.US).format(amount);
    }

    /** A price per item: whole when it is one, else one decimal ({@code 12.5}). */
    public static String format(double amount) {
        if (amount == Math.rint(amount) && Math.abs(amount) < 1e15) {
            return format((long) amount);
        }
        NumberFormat f = NumberFormat.getNumberInstance(Locale.US);
        f.setMaximumFractionDigits(1);
        f.setMinimumFractionDigits(1);
        return f.format(amount);
    }
}
