package us.rapidskies.horizons.pvp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import us.rapidskies.horizons.land.LandHook.PvpZone;

class PvpRulesTest {

    @Test
    void unclaimedLandNeedsBothPlayersOptedIn() {
        assertTrue(PvpRules.allowed(PvpZone.UNCLAIMED, false, true, true));
        assertFalse(PvpRules.allowed(PvpZone.UNCLAIMED, false, true, false));
        assertFalse(PvpRules.allowed(PvpZone.UNCLAIMED, false, false, true));
        assertFalse(PvpRules.allowed(PvpZone.UNCLAIMED, false, false, false));
    }

    @Test
    void claimsOverrideTheToggles() {
        for (boolean a : new boolean[] {true, false}) {
            for (boolean v : new boolean[] {true, false}) {
                assertFalse(PvpRules.allowed(PvpZone.DENY, false, a, v), "claim with PvP off");
                assertFalse(PvpRules.allowed(PvpZone.DENY, true, a, v), "a claim beats Wildlands");
                assertTrue(PvpRules.allowed(PvpZone.FORCE, false, a, v), "claim with PvP on");
                assertTrue(PvpRules.allowed(PvpZone.UNCLAIMED, true, a, v), "Wildlands force PvP");
            }
        }
    }

    private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");
    private static final Duration FIVE_MIN = Duration.ofMinutes(5);

    @Test
    void firstToggleIsFree() {
        assertEquals(new PvpRules.Toggle.Changed(true), PvpRules.toggle(false, true, NOW, null, null, FIVE_MIN));
    }

    @Test
    void sameStateIsUnchanged() {
        assertEquals(new PvpRules.Toggle.Unchanged(false), PvpRules.toggle(false, false, NOW, null, null, FIVE_MIN));
    }

    @Test
    void cooldownBetweenSwitches() {
        Instant twoMinAgo = NOW.minus(Duration.ofMinutes(2));
        assertEquals(new PvpRules.Toggle.Cooldown(Duration.ofMinutes(3)),
                PvpRules.toggle(false, true, NOW, twoMinAgo, null, FIVE_MIN));
        assertEquals(new PvpRules.Toggle.Changed(true),
                PvpRules.toggle(false, true, NOW, twoMinAgo, null, Duration.ZERO), "bypass");
        assertEquals(new PvpRules.Toggle.Changed(true),
                PvpRules.toggle(false, true, NOW, NOW.minus(FIVE_MIN), null, FIVE_MIN));
    }

    @Test
    void cantSwitchOffMidFightButCanSwitchOn() {
        Instant tagged = NOW.plusSeconds(20);
        assertEquals(new PvpRules.Toggle.InCombat(Duration.ofSeconds(20)),
                PvpRules.toggle(true, false, NOW, null, tagged, FIVE_MIN));
        assertEquals(new PvpRules.Toggle.InCombat(Duration.ofSeconds(20)),
                PvpRules.toggle(true, false, NOW, null, tagged, Duration.ZERO), "bypass doesn't skip the tag");
        assertEquals(new PvpRules.Toggle.Changed(true), PvpRules.toggle(false, true, NOW, null, tagged, FIVE_MIN));
        assertEquals(new PvpRules.Toggle.Changed(false),
                PvpRules.toggle(true, false, NOW, null, NOW.minusSeconds(1), FIVE_MIN), "tag ran out");
    }
}
