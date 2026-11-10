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
}
