package us.rapidskies.essentials.tools;

import java.math.BigDecimal;

/**
 * {@code /speed} levels, on EssentialsX's scale: 1 is the game's normal speed, 0 stands
 * still and 10 is the fastest the game allows (a speed of 1.0).
 */
public final class Speeds {

    public static final float DEFAULT_WALK = 0.2f;
    public static final float DEFAULT_FLY = 0.1f;
    public static final float MAX_LEVEL = 10f;

    private Speeds() {
    }

    /** The game's walk or fly speed for a level from 0 to 10. */
    public static float toGame(float level, boolean fly) {
        float normal = fly ? DEFAULT_FLY : DEFAULT_WALK;
        if (level < 1f) {
            return normal * level;
        }
        return normal + (level - 1f) / (MAX_LEVEL - 1f) * (1f - normal);
    }

    /** A level from 0 to 10 (decimals allowed), or null. */
    public static Float parseLevel(String input) {
        try {
            float level = Float.parseFloat(input.trim());
            return level >= 0f && level <= MAX_LEVEL ? level : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** {@code 2}, {@code 2.5}: no trailing zeros. */
    public static String format(float level) {
        return new BigDecimal(Float.toString(level)).stripTrailingZeros().toPlainString();
    }

    /** Whether {@code speed} is the game's normal speed (within float error). */
    public static boolean isDefault(float speed, boolean fly) {
        return Math.abs(speed - (fly ? DEFAULT_FLY : DEFAULT_WALK)) < 1e-4f;
    }
}
