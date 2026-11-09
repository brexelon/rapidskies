package us.rapidskies.common.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import us.rapidskies.common.rank.PlusColor;
import us.rapidskies.common.testing.TestServices;

@EnabledIf("us.rapidskies.common.testing.TestServices#dockerAvailable")
class ProfileRepositoryIT {

    private ProfileRepository profiles;

    @BeforeEach
    void setUp() throws Exception {
        TestServices.truncate("players");
        profiles = new ProfileRepository(TestServices.database());
    }

    @Test
    void firstLoginCreatesLaterLoginsUpdate() throws Exception {
        UUID id = UUID.randomUUID();
        PlayerProfile first = profiles.recordLogin(id, "Steve", "hub-1");
        assertTrue(first.isNew());
        assertNull(first.plusColor());
        assertEquals("hub-1", first.lastServer());

        Thread.sleep(5);
        PlayerProfile second = profiles.recordLogin(id, "Steve2", "smp-1");
        assertFalse(second.isNew());
        assertEquals(first.firstJoin(), second.firstJoin());
        assertEquals("Steve2", second.name());
        assertEquals("smp-1", second.lastServer());
    }

    @Test
    void plusColorIsStoredAndFoundByName() throws Exception {
        UUID id = UUID.randomUUID();
        profiles.recordLogin(id, "Alex", "hub-1");
        profiles.setPlusColor(id, PlusColor.DARK_AQUA);
        assertEquals(PlusColor.DARK_AQUA, profiles.find(id).orElseThrow().plusColor());
        assertEquals(id, profiles.findByName("alex").orElseThrow().uuid(), "name lookup ignores case");
        profiles.setPlusColor(id, null);
        assertNull(profiles.find(id).orElseThrow().plusColor());
    }
}
