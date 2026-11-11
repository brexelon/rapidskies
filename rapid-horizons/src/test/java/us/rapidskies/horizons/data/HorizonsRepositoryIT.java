package us.rapidskies.horizons.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.mariadb.MariaDBContainer;
import us.rapidskies.common.config.DatabaseSettings;
import us.rapidskies.common.db.Database;
import us.rapidskies.horizons.trade.Trade;

/**
 * Every statement {@link HorizonsRepository} runs, against a real MariaDB with the
 * {@code horizons} migration applied. Skipped without Docker; runs in CI.
 */
@EnabledIf("dockerAvailable")
class HorizonsRepositoryIT {

    private static MariaDBContainer mariadb;
    private static Database db;
    private static HorizonsRepository repo;

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
                mariadb.getDatabaseName(), mariadb.getUsername(), mariadb.getPassword(), 4), "horizons-test",
                LoggerFactory.getLogger("horizons-test"));
        // Core first, as on a server: rapid-core enables (and migrates) before rapid-horizons.
        db.migrate(HorizonsRepositoryIT.class.getClassLoader(), "core");
        db.migrate(HorizonsRepositoryIT.class.getClassLoader(), "horizons");
        repo = new HorizonsRepository(db);
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

    @Test
    void pvpAndTradeSwitchTimes() throws Exception {
        UUID steve = UUID.randomUUID();
        assertEquals(HorizonsRepository.PlayerRow.DEFAULT, repo.player(steve), "PvP is off until switched on");
        Instant at = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        repo.savePvp(steve, true, at);
        repo.saveTradesChanged(steve, at.plusSeconds(5));
        HorizonsRepository.PlayerRow row = repo.player(steve);
        assertTrue(row.pvp());
        assertEquals(Optional.of(at), row.pvpChangedAt());
        assertEquals(Optional.of(at.plusSeconds(5)), row.tradesChangedAt());
        repo.savePvp(steve, false, at.plusSeconds(10));
        assertEquals(false, repo.player(steve).pvp());
    }

    @Test
    void tradesAndDailyPoints() throws Exception {
        UUID alex = UUID.randomUUID();
        assertTrue(repo.trades(alex).isEmpty());
        repo.saveTrades(alex, Map.of(Trade.MINER, new HorizonsRepository.TradeRow(150, 1, true),
                Trade.FISHER, new HorizonsRepository.TradeRow(20, 0, true)));
        repo.saveTrades(alex, Map.of(Trade.MINER, new HorizonsRepository.TradeRow(400, 3, true),
                Trade.FISHER, new HorizonsRepository.TradeRow(20, 0, false)));
        Map<Trade, HorizonsRepository.TradeRow> trades = repo.trades(alex);
        assertEquals(new HorizonsRepository.TradeRow(400, 3, true), trades.get(Trade.MINER));
        assertEquals(new HorizonsRepository.TradeRow(20, 0, false), trades.get(Trade.FISHER),
                "a dropped Trade keeps its progress");

        LocalDate today = LocalDate.of(2026, 11, 9);
        assertEquals(0, repo.dailyPoints(alex, today));
        repo.addDailyPoints(alex, today, 120);
        repo.addDailyPoints(alex, today, 30);
        repo.addDailyPoints(alex, today.minusDays(1), 999);
        assertEquals(150, repo.dailyPoints(alex, today));
    }

    @Test
    void frontierRotation() throws Exception {
        FrontierRepository frontiers = new FrontierRepository(db);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        var first = frontiers.create(us.rapidskies.horizons.frontier.Modifier.OVERGROWTH, 11L,
                Optional.of("chapter-1"), now);
        assertEquals(us.rapidskies.horizons.frontier.FrontierRow.State.PREPARING, first.state());
        frontiers.rotate(java.util.OptionalInt.empty(), first.id(), now, now.plusSeconds(3600), now.plusSeconds(60));
        var second = frontiers.create(us.rapidskies.horizons.frontier.Modifier.DROUGHT, 22L, Optional.empty(), now);
        assertEquals(2, frontiers.live().size(), "the open one and the one being prepared");
        assertEquals(second.id(), frontiers.latest().orElseThrow().id());

        frontiers.rotate(java.util.OptionalInt.of(first.id()), second.id(), now.plusSeconds(5),
                now.plusSeconds(7200), null);
        var live = frontiers.live();
        assertEquals(1, live.size(), "exactly one Frontier open after a rotation");
        assertEquals(second.id(), live.getFirst().id());
        assertEquals(Optional.of(now.plusSeconds(7200)), live.getFirst().closesAt());
        assertTrue(live.getFirst().endOpensAt().isEmpty());

        // The second Frontier was made with no Chapter; it gets one later, but only once.
        assertEquals(frontiers.number(first.id()) + 1, frontiers.number(second.id()));
        assertTrue(frontiers.setChapterIfMissing(second.id(), "chapter-2"));
        assertEquals(false, frontiers.setChapterIfMissing(second.id(), "chapter-3"), "it already has one");
        assertEquals(Optional.of("chapter-2"), frontiers.live().stream().filter(r -> r.id() == second.id())
                .findFirst().orElseThrow().chapter());

        assertTrue(frontiers.markDragonKilled(second.id(), now), "the first kill pays");
        assertEquals(false, frontiers.markDragonKilled(second.id(), now), "a second kill doesn't");

        frontiers.setArchivePath(first.id(), "/archive/frontier_" + first.id());
        assertTrue(frontiers.archived().stream().anyMatch(r -> r.id() == first.id()));
        frontiers.setArchivePath(first.id(), null);
        assertTrue(frontiers.archived().stream().noneMatch(r -> r.id() == first.id()));

        UUID steve = UUID.randomUUID();
        assertTrue(frontiers.logoutFrontier(steve).isEmpty());
        frontiers.setLogoutFrontier(steve, second.id());
        assertEquals(second.id(), frontiers.logoutFrontier(steve).getAsInt());
        frontiers.setLogoutFrontier(steve, null);
        assertTrue(frontiers.logoutFrontier(steve).isEmpty());
        assertEquals(HorizonsRepository.PlayerRow.DEFAULT, repo.player(steve), "the new column leaves PvP alone");
    }

    @Test
    void chapterProgressAndCompletion() throws Exception {
        ChapterRepository chapters = new ChapterRepository(db);
        UUID alex = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        chapters.save(alex, "chapter-1", "deep-veins", new ChapterRepository.EntryRow(3, java.util.Set.of(),
                Optional.empty()));
        chapters.save(alex, "chapter-1", "wanderer", new ChapterRepository.EntryRow(2,
                java.util.Set.of("minecraft:forest", "minecraft:jungle"), Optional.empty()));
        chapters.save(alex, "chapter-1", "deep-veins", new ChapterRepository.EntryRow(2, java.util.Set.of(),
                Optional.of(now)));
        var progress = chapters.progress(alex, "chapter-1");
        assertEquals(3, progress.get("deep-veins").progress(), "progress never goes backwards");
        assertEquals(Optional.of(now), progress.get("deep-veins").completedAt());
        assertEquals(java.util.Set.of("minecraft:forest", "minecraft:jungle"), progress.get("wanderer").seen());
        assertTrue(chapters.progress(alex, "chapter-2").isEmpty());

        assertEquals(false, chapters.completed(alex, "chapter-1"));
        assertTrue(chapters.complete(alex, "chapter-1", "title:pathfinder", now), "first completion");
        assertEquals(false, chapters.complete(alex, "chapter-1", "title:pathfinder", now), "rewarded once");
        assertTrue(chapters.completed(alex, "chapter-1"));
        assertEquals(1, chapters.completions("chapter-1"));
    }
}
