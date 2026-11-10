package us.rapidskies.horizons.pvp;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Combat logging, free of Bukkit so every case is a unit test: who is punished for leaving
 * mid-fight, and how the extra punishment commands are filled in.
 */
public final class CombatLogRules {

    /** Why a player left (Paper's {@code PlayerQuitEvent.QuitReason}, mapped). */
    public enum Leave {
        /** They closed the game, pulled the cable, or switched server through the proxy. */
        DISCONNECTED,
        /** Their connection stopped answering: as good as pulling the cable. */
        TIMED_OUT,
        /** Kicked by staff or the anti-cheat. */
        KICKED,
        /** The server dropped them because of an error on its side: never their fault. */
        ERRONEOUS_STATE
    }

    private CombatLogRules() {
    }

    /**
     * Whether leaving now counts as combat logging.
     *
     * @param enabled     the feature is on
     * @param stopping    the server is shutting down (restarts kick everyone; never punished)
     * @param combatUntil when their combat tag ends (null or past: not in combat)
     * @param bypass      they have the bypass permission
     * @param punishKicks kicks count too
     */
    public static boolean punish(boolean enabled, boolean stopping, Leave leave, Instant now, Instant combatUntil,
                                 boolean bypass, boolean punishKicks) {
        if (!enabled || stopping || bypass || combatUntil == null || !combatUntil.isAfter(now)) {
            return false;
        }
        return switch (leave) {
            case DISCONNECTED, TIMED_OUT -> true;
            case KICKED -> punishKicks;
            case ERRONEOUS_STATE -> false;
        };
    }

    /** Seconds left in combat, rounded up so the countdown never shows 0 while still tagged. */
    public static long secondsLeft(Instant now, Instant combatUntil) {
        if (combatUntil == null || !combatUntil.isAfter(now)) {
            return 0;
        }
        Duration left = Duration.between(now, combatUntil);
        return left.toSeconds() + (left.toNanosPart() > 0 ? 1 : 0);
    }

    /** A punishment command with {@code <player>}, {@code <uuid>} and {@code <attacker>} filled in. */
    public static String command(String template, String player, UUID uuid, String attacker) {
        return template.replace("<player>", player).replace("<uuid>", uuid.toString())
                .replace("<attacker>", attacker == null ? "nobody" : attacker);
    }
}
