package us.rapidskies.common.tailwind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import us.rapidskies.common.db.Database;
import us.rapidskies.common.profile.ProfileRepository;
import us.rapidskies.common.tailwind.TailwindBank.Rules;
import us.rapidskies.common.testing.TestServices;

@EnabledIf("us.rapidskies.common.testing.TestServices#dockerAvailable")
class TailwindBankIT {

    private final Rules rules = new Rules(Duration.ofHours(2), 3);
    private final UUID steve = UUID.randomUUID();
    private Database db;
    private TailwindBank bank;
    private ProfileRepository profiles;

    @BeforeEach
    void setUp() throws Exception {
        TestServices.truncate("players");
        db = TestServices.database();
        bank = new TailwindBank(db);
        profiles = new ProfileRepository(db);
    }

    @Test
    void newPlayersGetTheStarterBankOnce() throws Exception {
        assertEquals(7200, profiles.recordLogin(steve, "Steve", "hub-1", 7200).tailwindBankedSeconds());
        bank.spend(steve, 200);
        assertEquals(7000, profiles.recordLogin(steve, "Steve", "smp-1", 7200).tailwindBankedSeconds(),
                "a returning player keeps their bank");
    }

    @Test
    void timeAwayIsSettledOnceOnTheNextLogin() throws Exception {
        profiles.recordLogin(steve, "Steve", "hub-1");
        bank.markLeft(steve);
        backdateLastSeen(Duration.ofDays(1));

        OptionalLong settled = bank.settleLogin(steve, rules);
        assertTrue(settled.isPresent());
        assertEquals(7200, settled.getAsLong(), 2, "a day away is worth two hours");
        assertEquals(OptionalLong.empty(), bank.settleLogin(steve, rules), "the stamp is cleared");
    }

    @Test
    void nothingToSettleWithoutAStamp() throws Exception {
        assertEquals(OptionalLong.empty(), bank.settleLogin(steve, rules), "never joined");
        profiles.recordLogin(steve, "Steve", "hub-1");
        assertEquals(OptionalLong.empty(), bank.settleLogin(steve, rules), "never left");
    }

    @Test
    void spendingStopsAtZero() throws Exception {
        profiles.recordLogin(steve, "Steve", "hub-1", 100);
        bank.spend(steve, 250);
        assertEquals(0, bank.banked(steve));
    }

    private void backdateLastSeen(Duration ago) throws Exception {
        db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE players SET last_seen = CURRENT_TIMESTAMP(3) - INTERVAL ? SECOND WHERE uuid = ?")) {
                ps.setLong(1, ago.toSeconds());
                ps.setString(2, steve.toString());
                return ps.executeUpdate();
            }
        });
    }
}
