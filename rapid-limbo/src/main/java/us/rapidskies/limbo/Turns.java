package us.rapidskies.limbo;

/**
 * Whether a move turned the player's view: rapid-core's AFK rule (AfkTracker), so "back at
 * the keyboard" means the same thing in limbo as on every other server. Walking doesn't
 * count, only turning.
 */
final class Turns {

    /** A turn smaller than this (degrees) is noise, not a player at the mouse. */
    static final float MIN_TURN = 1.0f;

    private Turns() {
    }

    static boolean turned(float fromYaw, float fromPitch, float toYaw, float toPitch) {
        return Math.abs(angle(fromYaw - toYaw)) >= MIN_TURN || Math.abs(fromPitch - toPitch) >= MIN_TURN;
    }

    /** Wraps a yaw difference into -180..180, so 359° → 1° is a 2° turn. */
    static float angle(float degrees) {
        float a = degrees % 360f;
        if (a > 180f) {
            a -= 360f;
        } else if (a < -180f) {
            a += 360f;
        }
        return a;
    }
}
