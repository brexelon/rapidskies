package us.rapidskies.common.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import us.rapidskies.common.economy.Ledger.Result;
import us.rapidskies.common.testing.TestServices;

@EnabledIf("us.rapidskies.common.testing.TestServices#dockerAvailable")
class LedgerIT {

    private Ledger bolts;
    private final UUID steve = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        TestServices.truncate("bolts_ledger", "bolts_balance", "mode_economy_ledger", "mode_balance");
        bolts = Ledger.bolts(TestServices.database());
    }

    @Test
    void creditAndDebitKeepLedgerAndBalanceInStep() throws Exception {
        assertEquals(0, bolts.balance(steve));
        assertEquals(new Result.Applied(100), bolts.credit(steve, 100, "daily_login", "hub-1", null));
        assertEquals(new Result.Applied(60), bolts.debit(steve, 40, "cosmetic:trail_flame", "hub-1", null));
        assertEquals(60, bolts.balance(steve));

        var history = bolts.history(steve, 10);
        assertEquals(2, history.size());
        assertEquals(-40, history.getFirst().delta(), "newest first");
        assertEquals(60, history.stream().mapToLong(Ledger.Entry::delta).sum(), "ledger sums to the balance");
    }

    @Test
    void debitNeverOverdraws() throws Exception {
        bolts.credit(steve, 10, "test", "test", null);
        assertEquals(new Result.Insufficient(10), bolts.debit(steve, 11, "test", "test", null));
        assertEquals(new Result.Insufficient(0), bolts.debit(UUID.randomUUID(), 1, "test", "test", null));
        assertEquals(1, bolts.history(steve, 10).size(), "a refused debit leaves no ledger row");
    }

    @Test
    void transactionIdsAreIdempotent() throws Exception {
        assertEquals(new Result.Applied(1100), bolts.credit(steve, 1100, "store", "tebex", "tbx-123:bolts"));
        assertEquals(new Result.Duplicate(1100), bolts.credit(steve, 1100, "store", "tebex", "tbx-123:bolts"));
        assertEquals(1100, bolts.balance(steve));
    }

    @Test
    void rejectsNonPositiveAmounts() {
        assertThrows(IllegalArgumentException.class, () -> bolts.credit(steve, 0, "x", "x", null));
        assertThrows(IllegalArgumentException.class, () -> bolts.debit(steve, -5, "x", "x", null));
    }

    @Test
    void concurrentDebitsFromManyServersNeverGoNegative() throws Exception {
        bolts.credit(steve, 100, "seed", "test", null);
        List<Callable<Result>> spends = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            spends.add(() -> bolts.debit(steve, 10, "race", "test", null));
        }
        int applied = 0;
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            for (Future<Result> f : pool.invokeAll(spends)) {
                if (f.get() instanceof Result.Applied) {
                    applied++;
                }
            }
        }
        assertEquals(10, applied, "exactly 100 / 10 debits succeed");
        assertEquals(0, bolts.balance(steve));
    }

    @Test
    void concurrentReplaysOfOneTransactionApplyOnce() throws Exception {
        List<Callable<Result>> replays = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            replays.add(() -> bolts.credit(steve, 500, "store", "tebex", "tbx-race"));
        }
        int applied = 0;
        try (ExecutorService pool = Executors.newFixedThreadPool(6)) {
            for (Future<Result> f : pool.invokeAll(replays)) {
                Result r = f.get();
                if (r instanceof Result.Applied) {
                    applied++;
                } else {
                    assertInstanceOf(Result.Duplicate.class, r);
                }
            }
        }
        assertEquals(1, applied);
        assertEquals(500, bolts.balance(steve));
    }

    @Test
    void modeCurrenciesAreSeparateFromEachOtherAndFromBolts() throws Exception {
        var db = TestServices.database();
        Ledger crowns = Ledger.mode(db, EconomyMode.HORIZONS);
        Ledger coins = Ledger.mode(db, EconomyMode.SKYREALMS);
        crowns.credit(steve, 5000, "trade:miner", "smp-1", "same-id");
        coins.credit(steve, 70, "shop:sell", "skyrealms-1", "same-id"); // txn ids are per mode
        bolts.credit(steve, 3, "test", "test", "same-id");
        assertEquals(5000, crowns.balance(steve));
        assertEquals(70, coins.balance(steve));
        assertEquals(3, bolts.balance(steve));
        assertEquals(new Result.Insufficient(70), coins.debit(steve, 71, "x", "x", null));
    }
}
