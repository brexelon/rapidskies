package us.rapidskies.common.rank;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RankTest {

    @Test
    void highestInheritedRankWins() {
        // LuckPerms reports inherited groups too: a Rapid++ player is also in rapidplus.
        Set<String> groups = Set.of("default", "rapidplus", "rapidplusplus");
        assertEquals(Rank.RAPID_PLUS_PLUS, Rank.resolve(groups::contains));
        assertEquals(Rank.DEFAULT, Rank.resolve(Set.of("default")::contains));
    }

    @Test
    void staffRoleOrderIsDisplayPriority() {
        Set<String> groups = Set.of("helper", "moderator", "builder");
        assertEquals(Optional.of(StaffRole.MODERATOR), StaffRole.resolve(groups::contains));
        assertEquals(Optional.of(StaffRole.BUILDER), StaffRole.resolve(Set.of("builder")::contains));
        assertTrue(StaffRole.resolve(Set.of("rapidplus")::contains).isEmpty());
    }

    @Test
    void byGroupMatchesLuckPermsNames() {
        assertEquals(Rank.RAPID_PLUS_PLUS_PLUS, Rank.byGroup("RapidPlusPlusPlus"));
    }
}
