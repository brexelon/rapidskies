package us.rapidskies.common.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import us.rapidskies.common.cosmetic.CosmeticRepository;
import us.rapidskies.common.economy.Ledger;
import us.rapidskies.common.profile.ProfileRepository;
import us.rapidskies.common.progress.ProgressRepository.Gain;
import us.rapidskies.common.progress.ProgressRepository.LevelReward;
import us.rapidskies.common.reward.LoginStreaks;
import us.rapidskies.common.store.StoreTransactions;
import us.rapidskies.common.testing.TestServices;

@EnabledIf("us.rapidskies.common.testing.TestServices#dockerAvailable")
class ProgressRepositoryIT {

    private static final NetworkLevels LEVELS = new NetworkLevels(100, 0, 100, 50);

    private Ledger bolts;
    private ProgressRepository progress;
    private UUID steve;

    @BeforeEach
    void setUp() throws Exception {
        TestServices.truncate("bolts_ledger", "bolts_balance", "cosmetics_owned", "season_progress", "players",
                "store_transactions");
        bolts = Ledger.bolts(TestServices.database());
        progress = new ProgressRepository(TestServices.database(), bolts);
        steve = new ProfileRepository(TestServices.database()).recordLogin(UUID.randomUUID(), "Steve", "hub-1").uuid();
    }

    private static ProgressRepository.Rules rules(boolean payBolts, ProgressRepository.Season season) {
        return new ProgressRepository.Rules() {
            @Override
            public NetworkLevels levels() {
                return LEVELS;
            }

            @Override
            public LevelReward reward(int level) {
                return new LevelReward(25, level == 3 ? List.of("tag:sprout") : List.of());
            }

            @Override
            public boolean payBolts() {
                return payBolts;
            }

            @Override
            public Optional<ProgressRepository.Season> season() {
                return Optional.ofNullable(season);
            }
        };
    }

    @Test
    void levelsPayOnceAndGrantCosmetics() throws Exception {
        Gain g = progress.add(steve, 250, rules(true, null)).orElseThrow();
        assertEquals(1, g.levelBefore());
        assertEquals(3, g.levelAfter());
        assertEquals(50, g.boltsPaid());
        assertEquals(List.of("tag:sprout"), g.newCosmetics());
        assertEquals(50, bolts.balance(steve));
        assertEquals(250, progress.xp(steve));
        // Small gains inside a level pay nothing.
        assertEquals(0, progress.add(steve, 10, rules(true, null)).orElseThrow().boltsPaid());
        assertTrue(new CosmeticRepository(TestServices.database()).load(steve).owned().contains("tag:sprout"));
    }

    @Test
    void sharedIpAccountsLevelWithoutBolts() throws Exception {
        Gain g = progress.add(steve, 100, rules(false, null)).orElseThrow();
        assertEquals(2, g.levelAfter());
        assertEquals(0, g.boltsPaid());
        assertEquals(0, bolts.balance(steve));
    }

    @Test
    void seasonTiersGrantFreeAndPremiumRewards() throws Exception {
        ProgressRepository.Season s1 = new ProgressRepository.Season("s1", 100, 10,
                t -> t == 1 ? List.of("title:first-light") : List.of(),
                t -> t == 2 ? List.of("tag:dawnstar") : List.of());
        Gain g = progress.add(steve, 150, rules(true, s1)).orElseThrow();
        assertEquals(0, g.tierBefore());
        assertEquals(1, g.tierAfter());
        assertEquals(List.of("title:first-light"), g.newCosmetics());
        // Premium rewards only once the track is bought.
        Gain g2 = progress.add(steve, 100, rules(true, s1)).orElseThrow();
        assertEquals(2, g2.tierAfter());
        assertFalse(g2.newCosmetics().contains("tag:dawnstar"));
        assertEquals(new ProgressRepository.SeasonStanding(250, false), progress.season(steve, "s1"));
        assertEquals(List.of("tag:dawnstar"), s1.premiumUpTo(250));
    }

    @Test
    void unknownPlayersGetNothing() throws Exception {
        assertTrue(progress.add(UUID.randomUUID(), 100, rules(true, null)).isEmpty());
    }

    @Test
    void loginStreaksCountDays() throws Exception {
        LoginStreaks streaks = new LoginStreaks(TestServices.database());
        LocalDate day = LocalDate.parse("2026-11-10");
        assertEquals(new LoginStreaks.Day(1, true), streaks.record(steve, day).orElseThrow());
        assertEquals(new LoginStreaks.Day(1, false), streaks.record(steve, day).orElseThrow());
        assertEquals(new LoginStreaks.Day(2, true), streaks.record(steve, day.plusDays(1)).orElseThrow());
        assertEquals(2, streaks.current(steve, day.plusDays(2)));
        assertEquals(0, streaks.current(steve, day.plusDays(3)));
        assertEquals(new LoginStreaks.Day(1, true), streaks.record(steve, day.plusDays(5)).orElseThrow());
    }

    @Test
    void storeTransactionsAndChargebackLookups() throws Exception {
        StoreTransactions store = new StoreTransactions(TestServices.database());
        store.delivered("tbx-9:bolts", steve, "bolts:1100");
        store.delivered("tbx-9:bonus", steve, "other");
        bolts.credit(steve, 1100, "store", "proxy", "tbx-9:bolts");
        bolts.credit(steve, 5, "store", "proxy", "tbx-9_x:bolts");
        assertEquals("bolts:1100", store.find("tbx-9").orElseThrow().packageName());
        List<Ledger.Entry> delivered = bolts.byTxnPrefix("tbx-9");
        assertEquals(1, delivered.size(), "the _ in another id must not match as a wildcard");
        assertEquals(1100, delivered.getFirst().delta());
        store.mark("tbx-9", steve, StoreTransactions.Status.CHARGEBACK, "test");
        assertEquals(StoreTransactions.Status.CHARGEBACK, store.find("tbx-9:x").orElseThrow().status());
    }
}
