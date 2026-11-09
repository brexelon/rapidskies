package us.rapidskies.common.rank;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;
import us.rapidskies.common.config.ConfigView;

class RankValuesTest {

    @Test
    void configOverridesOnlyTheRanksItNames() {
        RankValues sizes = RankValues.of(4, 6, 8, 12)
                .overriddenBy(ConfigView.of(Map.of("rapidplus", 7, "rapidplusplusplus", "15")));
        assertEquals(4, sizes.get(Rank.DEFAULT));
        assertEquals(7, sizes.get(Rank.RAPID_PLUS));
        assertEquals(8, sizes.get(Rank.RAPID_PLUS_PLUS));
        assertEquals(15, sizes.get(Rank.RAPID_PLUS_PLUS_PLUS));
    }

    @Test
    void needsOneDefaultPerRank() {
        assertThrows(IllegalArgumentException.class, () -> RankValues.of(1, 2));
    }
}
