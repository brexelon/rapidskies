package us.rapidskies.horizons.pvp;

import java.time.Duration;
import java.time.Instant;
import us.rapidskies.horizons.land.LandHook.PvpZone;

/**
 * Opt-in PvP (PLAN.md §5.2), free of Bukkit so every case is a unit test.
 *
 * <p>Order of precedence: a claim with PvP off stops every fight; a claim with PvP on, or a
 * Frontier's Wildlands, allows every fight; anywhere else both players must have switched
 * PvP on themselves.
 */
public final class PvpRules {

    private PvpRules() {
    }

    /** Whether {@code attacker} may hurt {@code victim}, judged where the victim stands. */
    public static boolean allowed(PvpZone zone, boolean wildlands, boolean attackerOn, boolean victimOn) {
        return switch (zone) {
            case DENY -> false;
            case FORCE -> true;
            case UNCLAIMED -> wildlands || (attackerOn && victimOn);
        };
    }

    /** The outcome of asking to switch personal PvP. */
    public sealed interface Toggle {
        /** Switched; {@code on} is the new state. */
        record Changed(boolean on) implements Toggle {
        }

        /** Already in the asked-for state. */
        record Unchanged(boolean on) implements Toggle {
        }

        /** Switched too recently; try again after {@code left}. */
        record Cooldown(Duration left) implements Toggle {
        }

        /** Switching off mid-fight isn't allowed; wait {@code left}. */
        record InCombat(Duration left) implements Toggle {
        }
    }

    /**
     * Decides a {@code /pvp} request.
     *
     * @param current     the player's state now
     * @param wanted      the state asked for
     * @param lastChange  when they last switched, or null if never
     * @param combatUntil when their combat tag ends (null or past: not in combat)
     * @param cooldown    time between switches (bypassed: pass {@link Duration#ZERO})
     */
    public static Toggle toggle(boolean current, boolean wanted, Instant now, Instant lastChange,
                                Instant combatUntil, Duration cooldown) {
        if (current == wanted) {
            return new Toggle.Unchanged(current);
        }
        // Switching on mid-fight is harmless; switching off would be an escape hatch.
        if (!wanted && combatUntil != null && combatUntil.isAfter(now)) {
            return new Toggle.InCombat(Duration.between(now, combatUntil));
        }
        if (lastChange != null) {
            Instant ready = lastChange.plus(cooldown);
            if (ready.isAfter(now)) {
                return new Toggle.Cooldown(Duration.between(now, ready));
            }
        }
        return new Toggle.Changed(wanted);
    }
}
