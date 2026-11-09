package us.rapidskies.core.world;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * A spawn point as written in config: {@code "world, x, y, z[, yaw, pitch]"}.
 *
 * <p>{@code world} is a world key ({@code minecraft:overworld} is the server's main world,
 * whatever its {@code level-name}; {@code rapidskies:spawn} a world rapid-core loads), or a
 * plain Bukkit world name. Yaw follows Minecraft: 0 faces south (+Z), 90 west, 180 north,
 * -90 east.
 */
public record SpawnPoint(String world, double x, double y, double z, float yaw, float pitch) {

    public SpawnPoint {
        if (world == null || world.isBlank()) {
            throw new IllegalArgumentException("spawn point has no world");
        }
        world = world.trim();
    }

    public static SpawnPoint parse(String text) {
        String[] parts = text.split(",");
        if (parts.length != 4 && parts.length != 6) {
            throw new IllegalArgumentException("expected \"world, x, y, z[, yaw, pitch]\", got \"" + text + "\"");
        }
        try {
            return new SpawnPoint(parts[0],
                    Double.parseDouble(parts[1].trim()),
                    Double.parseDouble(parts[2].trim()),
                    Double.parseDouble(parts[3].trim()),
                    parts.length == 6 ? Float.parseFloat(parts[4].trim()) : 0f,
                    parts.length == 6 ? Float.parseFloat(parts[5].trim()) : 0f);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a number in spawn point \"" + text + "\"", e);
        }
    }

    /** The same point in config syntax, rounded for humans (2 decimals, yaw/pitch 1). */
    public String format() {
        return String.join(", ", world, num(x, 2), num(y, 2), num(z, 2), num(yaw, 1), num(pitch, 1));
    }

    private static String num(double v, int decimals) {
        String s = BigDecimal.valueOf(v).setScale(decimals, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        return s.equals("-0") ? "0" : s.toLowerCase(Locale.ROOT);
    }
}
