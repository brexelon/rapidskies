package us.rapidskies.essentials.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
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
import us.rapidskies.essentials.data.EssentialsRepository.SetHome;
import us.rapidskies.essentials.data.EssentialsRepository.TopEntry;

/**
 * Every statement {@link EssentialsRepository} runs, against a real MariaDB with the
 * {@code core} and {@code essentials} migrations applied. Skipped without Docker; runs in CI.
 */
@EnabledIf("dockerAvailable")
class EssentialsRepositoryIT {

    private static MariaDBContainer mariadb;
    private static Database db;
    private static EssentialsRepository repo;

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
                mariadb.getDatabaseName(), mariadb.getUsername(), mariadb.getPassword(), 4), "essentials-test",
                LoggerFactory.getLogger("essentials-test"));
        ClassLoader cl = EssentialsRepositoryIT.class.getClassLoader();
        db.migrate(cl, "core");
        db.migrate(cl, "essentials");
        repo = new EssentialsRepository(db);
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

    private static Place place(double x) {
        return new Place("minecraft:overworld", x, 64, -20.5, 90f, 10f);
    }

    @Test
    void homesAreScopedLimitedAndMovable() throws Exception {
        UUID steve = UUID.randomUUID();
        assertEquals(SetHome.CREATED, repo.setHome("smp", steve, "base", place(1), 2));
        assertEquals(SetHome.CREATED, repo.setHome("smp", steve, "farm", place(2), 2));
        assertEquals(SetHome.LIMIT, repo.setHome("smp", steve, "mine", place(3), 2));
        // Moving an existing home works at the limit.
        assertEquals(SetHome.MOVED, repo.setHome("smp", steve, "base", place(4), 2));
        // Another mode's homes are separate.
        assertEquals(SetHome.CREATED, repo.setHome("skyrealms", steve, "base", place(5), 2));

        Map<String, Place> homes = repo.homes("smp", steve);
        assertEquals(List.of("base", "farm"), List.copyOf(homes.keySet()));
        assertEquals(place(4), homes.get("base"));
        assertEquals(place(5), repo.homes("skyrealms", steve).get("base"));

        assertTrue(repo.deleteHome("smp", steve, "farm"));
        assertFalse(repo.deleteHome("smp", steve, "farm"));
        assertEquals(SetHome.CREATED, repo.setHome("smp", steve, "mine", place(3), 2));
        assertEquals(Map.of(), repo.homes("smp", UUID.randomUUID()));
    }

    @Test
    void warps() throws Exception {
        UUID admin = UUID.randomUUID();
        assertFalse(repo.setWarp("hub-test", "shops", place(1), admin));
        assertFalse(repo.setWarp("hub-test", "arena", place(2), null));
        assertTrue(repo.setWarp("hub-test", "shops", place(3), admin));
        Map<String, Place> warps = repo.warps("hub-test");
        assertEquals(List.of("arena", "shops"), List.copyOf(warps.keySet()));
        assertEquals(place(3), warps.get("shops"));
        assertTrue(repo.deleteWarp("hub-test", "arena"));
        assertFalse(repo.deleteWarp("hub-test", "arena"));
        assertEquals(Map.of(), repo.warps("other"));
    }

    @Test
    void baltopListsPlayersOnly() throws Exception {
        UUID alex = UUID.randomUUID();
        UUID steve = UUID.randomUUID();
        UUID broke = UUID.randomUUID();
        UUID townBank = UUID.randomUUID();
        db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO players (uuid, name) VALUES (?, ?)")) {
                for (Object[] p : new Object[][] {{alex, "Alex"}, {steve, "Steve"}, {broke, "Broke"}}) {
                    ps.setString(1, p[0].toString());
                    ps.setString(2, (String) p[1]);
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO mode_balance (mode, uuid, balance) VALUES (?, ?, ?)")) {
                Object[][] rows = {{"horizons", alex, 500L}, {"horizons", steve, 1200L}, {"horizons", broke, 0L},
                        {"horizons", townBank, 99_999L}, {"skyrealms", alex, 7_000L}};
                for (Object[] r : rows) {
                    ps.setString(1, (String) r[0]);
                    ps.setString(2, r[1].toString());
                    ps.setLong(3, (Long) r[2]);
                    ps.executeUpdate();
                }
            }
            return null;
        });
        assertEquals(List.of(new TopEntry("Steve", 1200), new TopEntry("Alex", 500)), repo.top("horizons", 10));
        assertEquals(List.of(new TopEntry("Steve", 1200)), repo.top("horizons", 1));
        assertEquals(List.of(new TopEntry("Alex", 7000)), repo.top("skyrealms", 10));
    }
}
