package us.rapidskies.market.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.mariadb.MariaDBContainer;
import us.rapidskies.common.config.DatabaseSettings;
import us.rapidskies.common.db.Database;
import us.rapidskies.common.economy.EconomyMode;
import us.rapidskies.common.economy.Ledger;
import us.rapidskies.market.custody.Custody;
import us.rapidskies.market.model.AlertRule;
import us.rapidskies.market.model.Category;
import us.rapidskies.market.model.Fees;
import us.rapidskies.market.model.Listing;
import us.rapidskies.market.model.Notice;
import us.rapidskies.market.model.Sort;
import us.rapidskies.market.model.Stored;

/**
 * Every {@link MarketRepository} statement against a real MariaDB with the {@code core} and
 * {@code market} migrations, plus the auction house test list of PLAN.md §11: parallel buy
 * attempts on one listing, a crash in the middle of listing or buying, and buying with a full
 * inventory. Skipped without Docker; runs in CI.
 */
@EnabledIf("dockerAvailable")
class MarketRepositoryIT {

    private static final Fees FEES = new Fees(new BigDecimal("2"), new BigDecimal("5"));
    private static final AlertRule ALERTS = new AlertRule(25, 50, 3, 7);
    private static final byte[] ITEM = {10, 20, 30};

    private static MariaDBContainer mariadb;
    private static Database db;
    private static Ledger crowns;
    private static Ledger coins;
    private MarketRepository repo;
    private MarketRepository sky;
    private UUID seller;
    private UUID buyer;

    @TempDir
    Path journalDir;

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException e) {
            return false;
        }
    }

    @BeforeAll
    static void start() throws Exception {
        mariadb = new MariaDBContainer("mariadb:11.4");
        mariadb.start();
        db = Database.connect(new DatabaseSettings(mariadb.getHost(), mariadb.getMappedPort(3306),
                mariadb.getDatabaseName(), mariadb.getUsername(), mariadb.getPassword(), 24), "market-test",
                LoggerFactory.getLogger("market-test"));
        db.migrate(MarketRepositoryIT.class.getClassLoader(), "core");
        db.migrate(MarketRepositoryIT.class.getClassLoader(), "market");
        crowns = Ledger.mode(db, EconomyMode.HORIZONS);
        coins = Ledger.mode(db, EconomyMode.SKYREALMS);
    }

    @AfterAll
    static void stop() {
        if (db != null) {
            db.close();
        }
        if (mariadb != null) {
            mariadb.stop();
        }
    }

    @BeforeEach
    void fresh() throws Exception {
        db.withConnection(c -> {
            try (Statement s = c.createStatement()) {
                for (String t : List.of("market_listings", "market_collection", "market_notices",
                        "market_price_history", "mode_economy_ledger", "mode_balance")) {
                    s.executeUpdate("DELETE FROM " + t);
                }
            }
            return null;
        });
        repo = new MarketRepository(db, crowns, EconomyMode.HORIZONS, "smp-1", 7);
        sky = new MarketRepository(db, coins, EconomyMode.SKYREALMS, "skyrealms-1", 7);
        seller = UUID.randomUUID();
        buyer = UUID.randomUUID();
        crowns.credit(seller, 1_000, "test", "test", null);
        crowns.credit(buyer, 10_000, "test", "test", null);
    }

    private MarketRepository.NewListing listing(UUID who, long price) {
        return listing(who, price, Duration.ofHours(24), UUID.randomUUID());
    }

    private MarketRepository.NewListing listing(UUID who, long price, Duration d, UUID token) {
        return new MarketRepository.NewListing(token, who, "Steve", ITEM, "Diamond", "minecraft:diamond",
                Category.MISC, 4, price, FEES.listingFee(price), d);
    }

    private long list(UUID who, long price) throws SQLException {
        return ((MarketRepository.Created.Listed) repo.create(listing(who, price), 10)).id();
    }

    // ------------------------------------------------------------------ listing

    @Test
    void listingChargesTheFeeAndRespectsTheCap() throws Exception {
        var created = repo.create(listing(seller, 1_000), 2);
        var listed = assertInstanceOf(MarketRepository.Created.Listed.class, created);
        assertEquals(980, listed.balance(), "2% fee");
        assertEquals(980, crowns.balance(seller));
        repo.create(listing(seller, 1_000), 2);
        var third = repo.create(listing(seller, 1_000), 2);
        assertEquals(new MarketRepository.Created.TooMany(2), third);
        assertEquals(960, crowns.balance(seller), "a refused listing costs nothing");
    }

    @Test
    void listingWithoutTheFeeIsRefused() throws Exception {
        UUID broke = UUID.randomUUID();
        var r = repo.create(listing(broke, 1_000), 10);
        assertEquals(new MarketRepository.Created.CantAfford(0), r);
        assertEquals(0, repo.activeCount(broke));
    }

    @Test
    void theSameTokenListsOnce() throws Exception {
        UUID token = UUID.randomUUID();
        var first = (MarketRepository.Created.Listed) repo.create(listing(seller, 500, Duration.ofHours(1), token), 10);
        var again = repo.create(listing(seller, 500, Duration.ofHours(1), token), 10);
        assertEquals(new MarketRepository.Created.AlreadyListed(first.id()), again);
        assertEquals(990, crowns.balance(seller), "one fee");
    }

    @Test
    void parallelListingsCantPassTheCap() throws Exception {
        crowns.credit(seller, 100_000, "test", "test", null);
        List<Callable<MarketRepository.Created>> tasks = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            tasks.add(() -> repo.create(listing(seller, 100), 5));
        }
        List<MarketRepository.Created> results = race(tasks);
        long listed = results.stream().filter(r -> r instanceof MarketRepository.Created.Listed).count();
        assertEquals(5, listed);
        assertEquals(5, repo.activeCount(seller));
    }

    // ------------------------------------------------------------------ buying

    @Test
    void buyingMovesTheMoneyAndTheItemTogether() throws Exception {
        long id = list(seller, 1_000);
        var done = assertInstanceOf(MarketRepository.Bought.Done.class,
                repo.buy(id, buyer, "Alex", FEES, ALERTS));
        assertEquals(9_000, done.buyerBalance());
        assertEquals(980 + 950, done.sellerBalance(), "price less the 5% tax");
        assertEquals(50, done.tax());
        // The tax left the economy: buyer -1,000, seller +950.
        assertEquals(9_000, crowns.balance(buyer));
        assertEquals(1_930, crowns.balance(seller));

        Listing l = repo.find(id).orElseThrow();
        assertEquals(Listing.Status.SOLD, l.status());
        assertEquals(buyer, l.buyer());
        assertEquals("Alex", l.buyerName());

        List<Stored> bin = repo.collection(buyer, 0, 10);
        assertEquals(1, bin.size(), "bought items wait in the bin until delivered");
        assertEquals(done.storedId(), bin.getFirst().id());
        assertEquals(Stored.Reason.BOUGHT, bin.getFirst().reason());
        assertEquals(id, bin.getFirst().listingId());

        assertEquals(250.0, repo.averageUnitPrice("minecraft:diamond").orElseThrow(), "1,000 for 4");

        List<Notice> notices = repo.takeNotices(seller);
        assertEquals(1, notices.size());
        assertEquals(Notice.Kind.SOLD, notices.getFirst().kind());
        assertEquals(950, notices.getFirst().proceeds());
        assertEquals("Alex", notices.getFirst().otherName());
        assertTrue(repo.takeNotices(seller).isEmpty(), "shown once");

        assertEquals(new MarketRepository.Bought.Gone(), repo.buy(id, UUID.randomUUID(), "Late", FEES, ALERTS));
    }

    @Test
    void aBuyerWhoCantPayChangesNothing() throws Exception {
        long id = list(seller, 50_000);
        var r = repo.buy(id, buyer, "Alex", FEES, ALERTS);
        assertEquals(new MarketRepository.Bought.Insufficient(10_000), r);
        assertEquals(Listing.Status.ACTIVE, repo.find(id).orElseThrow().status(), "the SOLD was rolled back");
        assertEquals(10_000, crowns.balance(buyer));
        assertTrue(repo.collection(buyer, 0, 10).isEmpty());
    }

    @Test
    void sellersCantBuyTheirOwn() throws Exception {
        long id = list(seller, 100);
        assertEquals(new MarketRepository.Bought.OwnListing(), repo.buy(id, seller, "Steve", FEES, ALERTS));
    }

    /** PLAN.md §11: parallel buy attempts on one listing. Exactly one wins; nobody else pays. */
    @Test
    void parallelBuyersOnlyOneWins() throws Exception {
        long id = list(seller, 1_000);
        List<UUID> buyers = new ArrayList<>();
        List<Callable<MarketRepository.Bought>> tasks = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            UUID b = UUID.randomUUID();
            crowns.credit(b, 5_000, "test", "test", null);
            buyers.add(b);
            tasks.add(() -> repo.buy(id, b, "Buyer", FEES, ALERTS));
        }
        List<MarketRepository.Bought> results = race(tasks);
        assertEquals(1, results.stream().filter(r -> r instanceof MarketRepository.Bought.Done).count());
        assertEquals(15, results.stream().filter(r -> r instanceof MarketRepository.Bought.Gone).count());

        long paid = 0;
        int binRows = 0;
        for (UUID b : buyers) {
            paid += 5_000 - crowns.balance(b);
            binRows += repo.collection(b, 0, 10).size();
        }
        assertEquals(1_000, paid, "charged exactly once");
        assertEquals(1, binRows, "delivered exactly once");
        assertEquals(1_930, crowns.balance(seller), "paid exactly once");
    }

    /** Two servers in two modes racing on the same listing id can't cross: modes are separate. */
    @Test
    void anotherModeCantSeeOrBuyIt() throws Exception {
        long id = list(seller, 1_000);
        coins.credit(buyer, 10_000, "test", "test", null);
        assertEquals(new MarketRepository.Bought.Gone(), sky.buy(id, buyer, "Alex", FEES, ALERTS));
        assertTrue(sky.find(id).isEmpty());
        assertEquals(0, sky.browse(null, Sort.NEWEST, null, null, 0, 10).total());
    }

    /** Players buying each other's listings at once lock balances in the same order: no deadlock failures. */
    @Test
    void crosswiseBuysDontDeadlock() throws Exception {
        crowns.credit(seller, 100_000, "test", "test", null);
        crowns.credit(buyer, 100_000, "test", "test", null);
        List<Callable<MarketRepository.Bought>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            long a = list(seller, 100);
            long b = ((MarketRepository.Created.Listed) repo.create(listing(buyer, 100), 20)).id();
            tasks.add(() -> repo.buy(a, buyer, "Alex", FEES, ALERTS));
            tasks.add(() -> repo.buy(b, seller, "Steve", FEES, ALERTS));
        }
        List<MarketRepository.Bought> results = race(tasks);
        assertTrue(results.stream().allMatch(r -> r instanceof MarketRepository.Bought.Done), results.toString());
    }

    /** PLAN.md §11: a crash in the middle of buying. Nothing half-happens. */
    @Test
    void aCrashMidBuyRollsEverythingBack() throws Exception {
        long id = list(seller, 1_000);
        for (String point : List.of("buy:claimed", "buy:charged", "buy:stored")) {
            repo.checkpoint = p -> {
                if (p.equals(point)) {
                    throw new SQLException("server died at " + point);
                }
            };
            assertThrows(SQLException.class, () -> repo.buy(id, buyer, "Alex", FEES, ALERTS), point);
            repo.checkpoint = p -> {
            };
            assertEquals(Listing.Status.ACTIVE, repo.find(id).orElseThrow().status(), point);
            assertEquals(10_000, crowns.balance(buyer), point);
            assertEquals(980, crowns.balance(seller), point);
            assertTrue(repo.collection(buyer, 0, 10).isEmpty(), point);
            assertTrue(repo.takeNotices(seller).isEmpty(), point);
            assertTrue(repo.averageUnitPrice("minecraft:diamond").isEmpty(), point);
        }
        assertInstanceOf(MarketRepository.Bought.Done.class, repo.buy(id, buyer, "Alex", FEES, ALERTS),
                "and it can still be bought afterwards");
    }

    /**
     * PLAN.md §11: a crash in the middle of listing. The item was already out of the inventory
     * and in the custody journal; the next start finds no listing for the token and puts it in
     * the seller's bin. No fee was charged.
     */
    @Test
    void aCrashMidListingEndsWithTheItemInTheBin() throws Exception {
        UUID token = UUID.randomUUID();
        Custody custody = new Custody(journalDir, LoggerFactory.getLogger("custody"));
        custody.hold(Custody.Entry.outgoing(token, "horizons", seller, ITEM, "Diamond", 4));
        repo.checkpoint = p -> {
            if (p.equals("create:inserted")) {
                throw new SQLException("server died");
            }
        };
        assertThrows(SQLException.class, () -> repo.create(listing(seller, 1_000, Duration.ofHours(1), token), 10));
        repo.checkpoint = p -> {
        };
        assertEquals(1_000, crowns.balance(seller), "the fee was rolled back with the listing");

        // Next start: a fresh journal object over the same folder.
        Custody restarted = new Custody(journalDir, LoggerFactory.getLogger("custody"));
        assertEquals(1, restarted.recover("horizons", resolver()));
        List<Stored> bin = repo.collection(seller, 0, 10);
        assertEquals(1, bin.size());
        assertEquals(Stored.Reason.RETURNED, bin.getFirst().reason());
        // Recovery again (a second crash before the journal was deleted) can't add a second copy.
        restarted.hold(Custody.Entry.outgoing(token, "horizons", seller, ITEM, "Diamond", 4));
        restarted.unbusy(token.toString());
        restarted.recover("horizons", resolver());
        assertEquals(1, repo.collection(seller, 0, 10).size());
    }

    /** The other half: the listing committed but the server died before clearing the journal. */
    @Test
    void aCrashAfterListingDoesntDuplicateTheItem() throws Exception {
        UUID token = UUID.randomUUID();
        Custody custody = new Custody(journalDir, LoggerFactory.getLogger("custody"));
        custody.hold(Custody.Entry.outgoing(token, "horizons", seller, ITEM, "Diamond", 4));
        repo.create(listing(seller, 1_000, Duration.ofHours(1), token), 10);
        Custody restarted = new Custody(journalDir, LoggerFactory.getLogger("custody"));
        restarted.recover("horizons", resolver());
        assertTrue(repo.collection(seller, 0, 10).isEmpty(), "the listing holds it");
        assertEquals(1, repo.activeCount(seller));
    }

    /**
     * PLAN.md §11: buying with a full inventory. The sale puts the item in the buyer's bin in the
     * same transaction, so it's safe whether or not it can be delivered; delivery takes the row
     * once, even if asked twice at once, and a crash after the take puts it back.
     */
    @Test
    void buyingWithAFullInventoryLeavesItInTheBin() throws Exception {
        long id = list(seller, 1_000);
        var done = (MarketRepository.Bought.Done) repo.buy(id, buyer, "Alex", FEES, ALERTS);
        // Full inventory: nothing is delivered, and it stays.
        assertEquals(1, repo.collectionCount(buyer));

        List<Callable<Boolean>> takes = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            takes.add(() -> repo.take(done.storedId(), buyer));
        }
        assertEquals(1, race(takes).stream().filter(Boolean::booleanValue).count(), "handed out once");
        assertEquals(0, repo.collectionCount(buyer));

        // That take was journaled; the server died before the item reached the inventory.
        Custody custody = new Custody(journalDir, LoggerFactory.getLogger("custody"));
        custody.hold(Custody.Entry.incoming(done.storedId(), "horizons", buyer, ITEM, "Diamond", 4));
        Custody restarted = new Custody(journalDir, LoggerFactory.getLogger("custody"));
        restarted.recover("horizons", resolver());
        assertEquals(1, repo.collectionCount(buyer), "put back");
        long restored = repo.collection(buyer, 0, 10).getFirst().id();
        assertFalse(repo.take(restored, UUID.randomUUID()), "only its owner can take it");
        assertTrue(repo.take(restored, buyer));
    }

    private Custody.Resolver resolver() {
        return new Custody.Resolver() {
            @Override
            public boolean listed(UUID token) throws Exception {
                return repo.listingIdByToken(token).isPresent();
            }

            @Override
            public boolean stillStored(long storedId) throws Exception {
                return repo.collectionExists(storedId);
            }

            @Override
            public void restore(String token, Custody.Entry e) throws Exception {
                repo.store(token, e.owner(), e.item(), e.itemName(), e.amount(), Stored.Reason.RETURNED, null);
            }
        };
    }

    // ------------------------------------------------------------------ cancelling, expiry, removal

    @Test
    void cancellingReturnsTheItemButNotTheFee() throws Exception {
        long id = list(seller, 1_000);
        assertEquals(new MarketRepository.Closed.NotYours(), repo.cancel(id, buyer));
        var done = assertInstanceOf(MarketRepository.Closed.Done.class, repo.cancel(id, seller));
        assertEquals(0, done.noticeId(), "no notice for your own action");
        assertEquals(Listing.Status.CANCELLED, repo.find(id).orElseThrow().status());
        assertEquals(Stored.Reason.CANCELLED, repo.collection(seller, 0, 10).getFirst().reason());
        assertEquals(980, crowns.balance(seller));
        assertInstanceOf(MarketRepository.Closed.NotActive.class, repo.cancel(id, seller));
        assertEquals(new MarketRepository.Bought.Gone(), repo.buy(id, buyer, "Alex", FEES, ALERTS));
    }

    @Test
    void staffRemovalReturnsTheItemWithANotice() throws Exception {
        long id = list(seller, 1_000);
        UUID staff = UUID.randomUUID();
        var done = assertInstanceOf(MarketRepository.Closed.Done.class, repo.remove(id, staff));
        assertTrue(done.noticeId() > 0);
        assertEquals(Notice.Kind.REMOVED, repo.takeNotice(done.noticeId()).orElseThrow().kind());
        assertTrue(repo.takeNotice(done.noticeId()).isEmpty(), "shown once");
        assertEquals(Stored.Reason.REMOVED, repo.collection(seller, 0, 10).getFirst().reason());
        assertEquals(new MarketRepository.Closed.NotFound(), repo.remove(99_999, staff));
    }

    @Test
    void expiryRunsOnceEvenFromEveryServer() throws Exception {
        crowns.credit(seller, 100_000, "test", "test", null);
        for (int i = 0; i < 6; i++) {
            expire(list(seller, 100));
        }
        long live = list(seller, 100);
        assertEquals(1, repo.browse(null, Sort.NEWEST, null, null, 0, 50).total(), "expired ones aren't shown");
        List<Callable<List<MarketRepository.Closed.Done>>> servers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            servers.add(() -> repo.expireDue(100));
        }
        int expired = race(servers).stream().mapToInt(List::size).sum();
        assertEquals(6, expired);
        assertEquals(6, repo.collectionCount(seller));
        assertEquals(6, repo.takeNotices(seller).size());
        assertEquals(Listing.Status.ACTIVE, repo.find(live).orElseThrow().status());
    }

    @Test
    void expiredListingsCantBeBought() throws Exception {
        long id = list(seller, 100);
        expire(id);
        assertEquals(new MarketRepository.Bought.Gone(), repo.buy(id, buyer, "Alex", FEES, ALERTS));
        assertEquals(10_000, crowns.balance(buyer));
    }

    /**
     * Moves a listing's end into the past. Backdated rather than listed for zero seconds: the
     * Docker VM's clock isn't strictly monotonic on every host, which made "zero seconds" flaky.
     */
    private static void expire(long id) throws SQLException {
        db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE market_listings SET expires_at = NOW(3) - INTERVAL 1 HOUR WHERE id = ?")) {
                ps.setLong(1, id);
                return ps.executeUpdate();
            }
        });
    }

    // ------------------------------------------------------------------ browsing, history, alerts

    @Test
    void browseFiltersSortsAndPages() throws Exception {
        crowns.credit(seller, 100_000, "test", "test", null);
        repo.create(new MarketRepository.NewListing(UUID.randomUUID(), seller, "Steve", ITEM, "Oak Log",
                "minecraft:oak_log", Category.BLOCKS, 64, 640, 13, Duration.ofHours(1)), 20);
        repo.create(new MarketRepository.NewListing(UUID.randomUUID(), seller, "Steve", ITEM, "Diamond Pickaxe",
                "minecraft:diamond_pickaxe", Category.TOOLS, 1, 500, 10, Duration.ofHours(2)), 20);
        repo.create(new MarketRepository.NewListing(UUID.randomUUID(), seller, "Steve", ITEM, "100% Juice_",
                "minecraft:apple", Category.FOOD, 2, 30, 1, Duration.ofHours(3)), 20);

        assertEquals(3, repo.browse(null, Sort.NEWEST, null, null, 0, 10).total());
        assertEquals(List.of("Diamond Pickaxe"), names(repo.browse(Category.TOOLS, Sort.NEWEST, null, null, 0, 10)));
        assertEquals(List.of("Diamond Pickaxe"), names(repo.browse(null, Sort.NEWEST, "PICK", null, 0, 10)),
                "case-insensitive");
        assertEquals(List.of("Diamond Pickaxe"), names(repo.browse(null, Sort.NEWEST, "diamond_pick", null, 0, 10)),
                "the item key too; the _ is literal");
        assertEquals(List.of("100% Juice_"), names(repo.browse(null, Sort.NEWEST, "0%", null, 0, 10)),
                "wildcards are escaped");
        assertEquals(3, repo.browse(null, Sort.NEWEST, "steve", null, 0, 10).total(), "seller names match");
        assertEquals(List.of("Oak Log", "Apple"), names(repo.browse(null, Sort.PRICE_LOW, null, null, 0, 2))
                .stream().map(n -> n.startsWith("100") ? "Apple" : n).toList(), "10 each, then 15 each");
        assertEquals(List.of("Diamond Pickaxe"), names(repo.browse(null, Sort.PRICE_HIGH, null, null, 0, 1)));
        assertEquals(List.of("Oak Log"), names(repo.browse(null, Sort.ENDING, null, null, 0, 1)));
        var page2 = repo.browse(null, Sort.ENDING, null, null, 2, 2);
        assertEquals(3, page2.total());
        assertEquals(1, page2.listings().size());
        assertEquals(0, repo.browse(null, Sort.NEWEST, null, buyer, 0, 10).total(), "seller filter");
    }

    private static List<String> names(MarketRepository.Page page) {
        return page.listings().stream().map(Listing::itemName).toList();
    }

    @Test
    void repeatedFarBelowAverageSalesAreFlagged() throws Exception {
        crowns.credit(seller, 100_000, "test", "test", null);
        UUID honest = UUID.randomUUID();
        crowns.credit(honest, 100_000, "test", "test", null);
        // An honest market: diamonds go for 250 each.
        for (int i = 0; i < 3; i++) {
            repo.buy(list(seller, 1_000), honest, "Honest", FEES, ALERTS);
        }
        // Then the same seller dumps them on one buyer at 10 each.
        int flagged = 0;
        for (int i = 0; i < 3; i++) {
            var d = (MarketRepository.Bought.Done) repo.buy(list(seller, 40), buyer, "Alt", FEES, ALERTS);
            if (d.flagged()) {
                flagged++;
            }
        }
        assertEquals(3, flagged);
        assertEquals(3, repo.flaggedBetween(seller, buyer, 7));
        assertEquals(0, repo.flaggedBetween(seller, honest, 7));
        assertTrue(ALERTS.alert(repo.flaggedBetween(seller, buyer, 7)));
        List<Listing> history = repo.history(seller, 0, 50);
        assertEquals(6, history.size());
        assertEquals(buyer, history.getFirst().buyer(), "newest first");
    }

    @Test
    void storeIsIdempotentPerToken() throws Exception {
        long a = repo.store("custody:x", seller, ITEM, "Diamond", 4, Stored.Reason.RETURNED, null);
        long b = repo.store("custody:x", seller, ITEM, "Diamond", 4, Stored.Reason.RETURNED, null);
        assertEquals(a, b);
        assertEquals(1, repo.collectionCount(seller));
        assertTrue(repo.collectionExists(a));
    }

    @Test
    void ledgerRowsDescribeEveryMove() throws Exception {
        long id = list(seller, 1_000);
        repo.buy(id, buyer, "Alex", FEES, ALERTS);
        List<String> reasons = db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT reason, delta, txn_id FROM mode_economy_ledger "
                    + "WHERE mode = 'horizons' AND reason LIKE 'market:%' ORDER BY id")) {
                List<String> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(rs.getString(1) + " " + rs.getLong(2) + " " + rs.getString(3));
                    }
                }
                return out;
            }
        });
        assertEquals(3, reasons.size(), reasons.toString());
        assertTrue(reasons.get(0).startsWith("market:fee -20 market:fee:"), reasons.toString());
        assertEquals("market:buy -1000 market:buy:" + id, reasons.get(1));
        assertEquals("market:sale 950 market:sale:" + id, reasons.get(2));
    }

    /** Runs every task at the same moment and collects the results (exceptions fail the test). */
    private static <T> List<T> race(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> t : tasks) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return t.call();
                }));
            }
            go.countDown();
            List<T> out = new ArrayList<>();
            for (Future<T> f : futures) {
                out.add(f.get());
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }
}
