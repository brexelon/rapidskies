package us.rapidskies.hub.parkour;

import java.time.Duration;

/** How times read to players. */
public final class TimeFormat {

    private TimeFormat() {
    }

    /** A run time: {@code 0:42.317}, {@code 12:03.004}, {@code 1:02:03.004}. */
    public static String run(long millis) {
        long ms = Math.max(0, millis);
        long hours = ms / 3_600_000;
        long minutes = ms / 60_000 % 60;
        long seconds = ms / 1000 % 60;
        long rest = ms % 1000;
        return hours > 0
                ? String.format("%d:%02d:%02d.%03d", hours, minutes, seconds, rest)
                : String.format("%d:%02d.%03d", minutes, seconds, rest);
    }

    /** The live timer: tenths only, so the action bar doesn't flicker. {@code 0:42.3}. */
    public static String timer(long millis) {
        long ms = Math.max(0, millis);
        long minutes = ms / 60_000;
        long seconds = ms / 1000 % 60;
        return String.format("%d:%02d.%d", minutes, seconds, ms / 100 % 10);
    }

    /** A countdown in the two largest units: {@code 2d 4h}, {@code 3h 12m}, {@code 45m}, {@code <1m}. */
    public static String countdown(Duration left) {
        long minutes = Math.max(0, left.toMinutes());
        long days = minutes / 1440;
        long hours = minutes / 60 % 24;
        long mins = minutes % 60;
        if (days > 0) {
            return days + "d " + hours + "h";
        }
        if (hours > 0) {
            return hours + "h " + mins + "m";
        }
        return mins > 0 ? mins + "m" : "<1m";
    }
}
