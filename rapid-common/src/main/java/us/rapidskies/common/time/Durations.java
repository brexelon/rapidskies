package us.rapidskies.common.time;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Durations typed by staff ({@code 30d}, {@code 1h30m}, {@code 2w}) and their short display
 * form ({@code 29d 23h}).
 */
public final class Durations {

    private static final Pattern PART = Pattern.compile("(\\d{1,6})(mo|y|w|d|h|m|s)");

    private Durations() {
    }

    /** Words that mean "no end", accepted wherever a duration is. */
    public static boolean isPermanent(String input) {
        return switch (input.toLowerCase(Locale.ROOT)) {
            case "perm", "permanent", "forever" -> true;
            default -> false;
        };
    }

    /**
     * Parses one or more {@code <number><unit>} parts with no spaces: {@code s m h d w},
     * {@code mo} (30 days) and {@code y} (365 days). Empty if the input isn't a positive
     * duration in that form.
     */
    public static Optional<Duration> parse(String input) {
        if (input == null || input.isEmpty()) {
            return Optional.empty();
        }
        Matcher m = PART.matcher(input.toLowerCase(Locale.ROOT));
        Duration total = Duration.ZERO;
        int end = 0;
        while (m.find()) {
            if (m.start() != end) {
                return Optional.empty();
            }
            long n = Long.parseLong(m.group(1));
            total = total.plus(switch (m.group(2)) {
                case "y" -> Duration.ofDays(365 * n);
                case "mo" -> Duration.ofDays(30 * n);
                case "w" -> Duration.ofDays(7 * n);
                case "d" -> Duration.ofDays(n);
                case "h" -> Duration.ofHours(n);
                case "m" -> Duration.ofMinutes(n);
                default -> Duration.ofSeconds(n);
            });
            end = m.end();
        }
        if (end != input.length() || total.isZero()) {
            return Optional.empty();
        }
        return Optional.of(total);
    }

    /** The two largest non-zero units: "29d 23h", "2h 15m", "45s". Negative counts as zero. */
    public static String format(Duration duration) {
        long seconds = Math.max(0, duration.toSeconds());
        long[] amounts = {seconds / 86_400, seconds % 86_400 / 3_600, seconds % 3_600 / 60, seconds % 60};
        String[] units = {"d", "h", "m", "s"};
        StringBuilder out = new StringBuilder();
        int shown = 0;
        for (int i = 0; i < amounts.length && shown < 2; i++) {
            if (amounts[i] > 0) {
                if (!out.isEmpty()) {
                    out.append(' ');
                }
                out.append(amounts[i]).append(units[i]);
                shown++;
            } else if (shown > 0) {
                break; // "1d 5m" would read oddly; stop at the first gap after the largest unit
            }
        }
        return out.isEmpty() ? "0s" : out.toString();
    }
}
