package us.rapidskies.horizons.trade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import us.rapidskies.horizons.data.HorizonsRepository.TradeRow;
import us.rapidskies.horizons.trade.TradeOutbox.Batch;
import us.rapidskies.horizons.trade.TradeOutbox.Payout;

/**
 * The fix for "a database failure during a Trade payout drops that batch's Crowns": what a
 * write couldn't do goes back and is retried, never lost and never paid twice.
 */
class TradeOutboxTest {

    private static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 10);

    @Test
    void aFailedBatchComesBackWhole() {
        TradeOutbox box = new TradeOutbox(STEVE);
        Payout p1 = new Payout(Trade.MINER, 12, "trade:a:1");
        box.add(Map.of(Trade.MINER, new TradeRow(100, 1, true)), Map.of(DAY, 40L), List.of(p1));
        Batch out = box.take();
        assertNotNull(out);
        assertNull(box.take(), "one batch out at a time");
        assertFalse(box.idle());

        // The database refused everything: it all goes back, with the same transaction id.
        box.giveBack(out);
        Batch retry = box.take();
        assertEquals(out, retry);
        assertEquals("trade:a:1", retry.payouts().getFirst().txnId(), "a retry can't pay twice");
    }

    @Test
    void newerRowsWinAndPayoutsKeepTheirOrder() {
        TradeOutbox box = new TradeOutbox(STEVE);
        box.add(Map.of(Trade.MINER, new TradeRow(100, 1, true)), Map.of(DAY, 10L),
                List.of(new Payout(Trade.MINER, 5, "t1")));
        Batch first = box.take();
        // While it's out, more work arrives.
        box.add(Map.of(Trade.MINER, new TradeRow(180, 2, true)), Map.of(DAY, 7L),
                List.of(new Payout(Trade.MINER, 3, "t2")));
        box.giveBack(first);
        Batch next = box.take();
        assertEquals(new TradeRow(180, 2, true), next.rows().get(Trade.MINER), "the newer row isn't overwritten");
        assertEquals(17L, next.points().get(DAY), "points add up");
        assertEquals(List.of("t1", "t2"), next.payouts().stream().map(Payout::txnId).toList());
        assertEquals(8, next.crowns());
    }

    @Test
    void aPartlyWrittenBatchReturnsOnlyTheRest() {
        TradeOutbox box = new TradeOutbox(STEVE);
        box.add(Map.of(Trade.FISHER, new TradeRow(5, 0, true)), Map.of(),
                List.of(new Payout(Trade.FISHER, 1, "t1"), new Payout(Trade.FISHER, 2, "t2")));
        Batch out = box.take();
        // Rows and the first payout went through; the second didn't.
        box.giveBack(new Batch(STEVE, Map.of(), Map.of(), out.payouts().subList(1, 2)));
        Batch rest = box.take();
        assertTrue(rest.rows().isEmpty());
        assertEquals(List.of("t2"), rest.payouts().stream().map(Payout::txnId).toList());
        box.giveBack(Batch.empty(STEVE));
        assertTrue(box.idle());
    }

    @Test
    void pauseHoldsBatchesBackForALogin() throws InterruptedException {
        TradeOutbox box = new TradeOutbox(STEVE);
        box.add(Map.of(Trade.HUNTER, new TradeRow(50, 0, true)), Map.of(), List.of());
        box.pause();
        assertNull(box.take(), "nothing goes out while a login reads the progress");
        assertEquals(new TradeRow(50, 0, true), box.pending().rows().get(Trade.HUNTER));
        box.resume();
        assertNotNull(box.take());
    }

    @Test
    void theJournalRoundTrips(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(TradeJournal.FILE);
        Batch batch = new Batch(STEVE, Map.of(Trade.MINER, new TradeRow(400, 3, true),
                Trade.FARMER, new TradeRow(20, 0, false)), Map.of(DAY, 120L),
                List.of(new Payout(Trade.MINER, 30, "trade:" + STEVE + ":miner:1:1")));
        TradeJournal.save(file, List.of(batch, Batch.empty(UUID.randomUUID())));
        assertTrue(Files.exists(file));
        assertEquals(List.of(batch), TradeJournal.load(file), "empty batches aren't kept");

        TradeJournal.save(file, List.of());
        assertFalse(Files.exists(file), "nothing left: no file");
        assertEquals(List.of(), TradeJournal.load(file));
    }

    @Test
    void aBrokenJournalEntryIsSkipped(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(TradeJournal.FILE);
        Files.writeString(file, "[{\"player\":\"not-a-uuid\"},{\"player\":\"" + STEVE
                + "\",\"rows\":{},\"points\":{},\"payouts\":[{\"trade\":\"miner\",\"crowns\":4,\"txn\":\"x\"}]}]");
        List<Batch> loaded = TradeJournal.load(file);
        assertEquals(1, loaded.size());
        assertEquals(4, loaded.getFirst().crowns());
    }
}
