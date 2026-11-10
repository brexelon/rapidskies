package us.rapidskies.skyrealms.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.bukkit.Material;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.mariadb.MariaDBContainer;
import us.rapidskies.common.config.DatabaseSettings;
import us.rapidskies.common.db.Database;
import us.rapidskies.common.economy.EconomyMode;
import us.rapidskies.common.economy.Ledger;
import us.rapidskies.skyrealms.SkyRealmsSettings;
import us.rapidskies.skyrealms.route.Route;

/**
 * Every statement {@link SkyRealmsRepository} runs, against a real MariaDB with the {@code core}
 * (ledgers) and {@code skyrealms} migrations applied: ascending pays once, shop sales share
 * one soft cap even in parallel, a route delivers each cycle once and only when paid, seasons
 * close once. Skipped without Docker; runs in CI.
 */
@EnabledIf("dockerAvailable")
class SkyRealmsRepositoryIT {

    private static MariaDBContainer mariadb;
    private static Database db;
    private static SkyRealmsRepository repo;
    private static Ledger coins;

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
                mariadb.getDatabaseName(), mariadb.getUsername(), mariadb.getPassword(), 8), "skyrealms-test",
                LoggerFactory.getLogger("skyrealms-test"));
        db.migrate(SkyRealmsRepositoryIT.class.getClassLoader(), "core");
        db.migrate(SkyRealmsRepositoryIT.class.getClassLoader(), "skyrealms");
        repo = new SkyRealmsRepository(db);
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

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    @Test
    void tiersAndProgress() throws Exception {
        UUID island = UUID.randomUUID();
        repo.ensureIsland(island, "meadow", now());
        repo.ensureIsland(island, "desert", now()); // an existing row is kept
        assertEquals("meadow", repo.tiers().get(island));
        repo.addProgress(List.of(new SkyRealmsRepository.Progress(island, "desert", "mine:cobblestone", 40),
                new SkyRealmsRepository.Progress(island, "desert", "sell", 100)));
        repo.addProgress(List.of(new SkyRealmsRepository.Progress(island, "desert", "mine:cobblestone", 2)));
        long mined = repo.progress().stream().filter(p -> p.island().equals(island) && p.goal().equals("mine:cobblestone"))
                .mapToLong(SkyRealmsRepository.Progress::progress).sum();
        assertEquals(42, mined);
        repo.forgetIsland(island);
        assertFalse(repo.tiers().containsKey(island));
        assertTrue(repo.progress().stream().noneMatch(p -> p.island().equals(island)));
    }

    @Test
    void ascendingPaysOnceAndOnlyWithTheMoney() throws Exception {
        UUID island = UUID.randomUUID();
        UUID leader = UUID.randomUUID();
        var poor = repo.ascend(coins, island, "meadow", "desert", now(), leader, 5000, "test", "realm:" + island + ":desert");
        assertEquals(SkyRealmsRepository.Ascend.INSUFFICIENT, poor.outcome());
        assertEquals("meadow", repo.tiers().get(island), "no money, no tier");
        coins.credit(leader, 6000, "test", "test", null);
        var ok = repo.ascend(coins, island, "meadow", "desert", now(), leader, 5000, "test", "realm:" + island + ":desert2");
        assertEquals(SkyRealmsRepository.Ascend.OK, ok.outcome());
        assertEquals(1000, ok.balance());
        assertEquals("desert", repo.tiers().get(island));
        var again = repo.ascend(coins, island, "meadow", "desert", now(), leader, 5000, "test", "realm:" + island + ":x");
        assertEquals(SkyRealmsRepository.Ascend.NOT_CURRENT, again.outcome(), "two clicks ascend once");
        assertEquals(1000, coins.balance(leader));
    }

    @Test
    void parallelSalesShareOneSoftCap() throws Exception {
        UUID seller = UUID.randomUUID();
        SkyRealmsSettings.Shop cfg = new SkyRealmsSettings.Shop(1000, 50, 25, List.of());
        LocalDate day = LocalDate.of(2026, 11, 10);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<SkyRealmsRepository.Sale>> sales = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                String txn = "shop:" + seller + ":" + i;
                sales.add(() -> repo.sell(coins, seller, day, 500, cfg, 1.0, "test", txn));
            }
            long paid = 0;
            for (Future<SkyRealmsRepository.Sale> f : pool.invokeAll(sales)) {
                paid += f.get().paid();
            }
            // 2000 worth: the first 1000 at full price, the next 1000 at half, whatever the order.
            assertEquals(1500, paid);
            assertEquals(1500, coins.balance(seller));
            assertEquals(2000, repo.earnedToday(seller, day));
            assertEquals(0, repo.earnedToday(seller, day.plusDays(1)), "a new day starts fresh");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void routesDeliverOncePerCycleAndOnlyWhenPaid() throws Exception {
        UUID sellerIsland = UUID.randomUUID();
        UUID buyerIsland = UUID.randomUUID();
        UUID seller = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        Instant t0 = now();
        long id = repo.insertRoute(new Route(0, sellerIsland, buyerIsland, "Cactus Co", "Builders", Material.CACTUS, 64,
                2000, 168, Route.State.OFFERED, seller, Route.Side.SELLER, Optional.empty(), Optional.empty(), 0, 0, t0));
        assertTrue(repo.signRoute(id, t0, t0.plus(Duration.ofDays(7))));
        assertFalse(repo.signRoute(id, t0, t0), "an offer is accepted once");
        repo.setChest(id, new Route.ChestPos("skyrealms_islands", 10, 101, -4));
        Route r = repo.route(id).orElseThrow();
        assertEquals(Route.State.ACTIVE, r.state());
        assertEquals(Optional.of(new Route.ChestPos("skyrealms_islands", 10, 101, -4)), r.chest());

        Instant next = t0.plus(Duration.ofDays(14));
        var unpaid = repo.deliver(coins, r, 1, buyer, seller, 60, t0, next, "test");
        assertEquals(SkyRealmsRepository.Delivery.INSUFFICIENT, unpaid.outcome());
        assertTrue(repo.depot(buyerIsland).isEmpty(), "no payment, no goods");

        coins.credit(buyer, 2500, "test", "test", null);
        var done = repo.deliver(coins, r, 1, buyer, seller, 60, t0, next, "test");
        assertEquals(SkyRealmsRepository.Delivery.DELIVERED, done.outcome());
        assertEquals(500, done.payerBalance());
        assertEquals(1940, done.payeeBalance(), "the seller gets the price less tax");
        assertEquals(Map.of("cactus", 64L), repo.depot(buyerIsland));
        var twice = repo.deliver(coins, r, 1, buyer, seller, 60, t0, next, "test");
        assertEquals(SkyRealmsRepository.Delivery.GONE, twice.outcome(), "cycle 1 happened already");
        assertEquals(500, coins.balance(buyer));

        assertEquals(1, repo.recordMiss(id, next));
        assertEquals(2, repo.recordMiss(id, next));
        assertEquals(1, repo.route(id).orElseThrow().deliveries());

        assertFalse(repo.takeFromDepot(buyerIsland, "cactus", 65), "never more than there is");
        assertTrue(repo.takeFromDepot(buyerIsland, "cactus", 64));
        assertTrue(repo.depot(buyerIsland).isEmpty());
        repo.addToDepot(sellerIsland, "cactus", 10);
        assertEquals(Map.of("cactus", 10L), repo.depot(sellerIsland));

        assertTrue(repo.liveRoutes().stream().anyMatch(x -> x.id() == id));
        assertTrue(repo.endRoute(id, "cancelled", now()));
        assertFalse(repo.endRoute(id, "cancelled", now()));
        assertTrue(repo.liveRoutes().stream().noneMatch(x -> x.id() == id));
        assertEquals(SkyRealmsRepository.Delivery.GONE,
                repo.deliver(coins, r.delivered(next), 2, buyer, seller, 60, t0, next, "test").outcome(),
                "an ended route delivers nothing");
    }

    @Test
    void seasonsCloseOnce() throws Exception {
        int id = repo.lastSeasonId() + 1;
        UUID island = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        Instant t0 = now();
        repo.startSeason(new SkyRealmsRepository.Season(id, t0, t0.plus(Duration.ofDays(90))),
                Map.of(island, new BigDecimal("1500.5")));
        assertEquals(id, repo.openSeason().orElseThrow().id());
        UUID newcomer = UUID.randomUUID();
        repo.addBaseline(id, newcomer, BigDecimal.ZERO);
        repo.addBaseline(id, island, BigDecimal.TEN); // an existing baseline is kept
        Map<UUID, BigDecimal> base = repo.baselines(id);
        assertEquals(0, new BigDecimal("1500.5").compareTo(base.get(island)));
        assertEquals(0, BigDecimal.ZERO.compareTo(base.get(newcomer)));

        var award = new SkyRealmsRepository.AwardRow(id, member, island, "Sky Farm", 1, new BigDecimal("9000"),
                "title:season-" + id + "-champion", 500);
        assertTrue(repo.closeSeason(id, now(), List.of(award)));
        assertFalse(repo.closeSeason(id, now(), List.of(award)), "a season ends once");
        assertTrue(repo.openSeason().isEmpty());
        List<SkyRealmsRepository.AwardRow> awards = repo.awards(id);
        assertEquals(1, awards.size());
        assertEquals("title:season-" + id + "-champion", awards.getFirst().cosmetic());
    }

    @Test
    void expeditionsAreCounted() throws Exception {
        long before = repo.expeditionCount();
        long id = repo.startExpedition("sunken-ship", now());
        repo.endExpedition(id, now(), 3, 420, true);
        assertEquals(before + 1, repo.expeditionCount());
    }
}
