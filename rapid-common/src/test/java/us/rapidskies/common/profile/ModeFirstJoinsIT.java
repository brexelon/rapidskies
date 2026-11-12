package us.rapidskies.common.profile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import us.rapidskies.common.economy.EconomyMode;
import us.rapidskies.common.testing.TestServices;

@EnabledIf("us.rapidskies.common.testing.TestServices#dockerAvailable")
class ModeFirstJoinsIT {

    private ModeFirstJoins joins;

    @BeforeEach
    void setUp() throws Exception {
        TestServices.truncate("mode_first_joins");
        joins = new ModeFirstJoins(TestServices.database());
    }

    @Test
    void onlyTheFirstJoinOfEachModeCounts() throws Exception {
        UUID id = UUID.randomUUID();
        assertTrue(joins.record(id, EconomyMode.SKYREALMS));
        assertFalse(joins.record(id, EconomyMode.SKYREALMS), "second visit");
        assertTrue(joins.record(id, EconomyMode.HORIZONS), "each mode has its own first join");
        assertTrue(joins.record(UUID.randomUUID(), EconomyMode.SKYREALMS), "and each player");
    }
}
