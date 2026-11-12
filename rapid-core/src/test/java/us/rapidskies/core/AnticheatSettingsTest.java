package us.rapidskies.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import us.rapidskies.common.config.ConfigView;

class AnticheatSettingsTest {

    @Test
    void defaultsWhenTheSectionIsMissing() {
        CoreSettings.Anticheat a = CoreSettings.anticheat(ConfigView.empty());
        assertEquals(40, a.olderClientGraceTicks());
        assertEquals(Set.copyOf(CoreSettings.DEFAULT_GRACE_CHECKS), a.olderClientGraceChecks());
    }

    @Test
    void readsTicksAndChecks() {
        CoreSettings.Anticheat a = CoreSettings.anticheat(ConfigView.of(Map.of("anticheat",
                Map.of("older-client-grace", Map.of("ticks", 10, "checks", List.of(" Simulation ", "", "Phase"))))));
        assertEquals(10, a.olderClientGraceTicks());
        assertEquals(Set.of("Simulation", "Phase"), a.olderClientGraceChecks());
    }

    @Test
    void anEmptyListTurnsTheChecksOffAndNegativeTicksAreZero() {
        CoreSettings.Anticheat a = CoreSettings.anticheat(ConfigView.of(Map.of("anticheat",
                Map.of("older-client-grace", Map.of("ticks", -5, "checks", List.of())))));
        assertEquals(0, a.olderClientGraceTicks());
        assertEquals(Set.of(), a.olderClientGraceChecks());
    }

    @Test
    void theBundledConfigMatchesTheDefaults() throws Exception {
        try (var in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            CoreSettings.Anticheat a = CoreSettings.anticheat(ConfigView.load(in));
            assertEquals(40, a.olderClientGraceTicks());
            assertEquals(Set.copyOf(CoreSettings.DEFAULT_GRACE_CHECKS), a.olderClientGraceChecks());
        }
    }
}
