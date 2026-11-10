package us.rapidskies.essentials;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import us.rapidskies.common.config.ConfigView;
import us.rapidskies.essentials.teleport.TeleportKind;

class EssentialsSettingsTest {

    private static EssentialsSettings bundled() throws IOException {
        try (InputStream in = EssentialsSettingsTest.class.getClassLoader().getResourceAsStream("config.yml")) {
            return EssentialsSettings.from(ConfigView.load(in));
        }
    }

    @Test
    void bundledConfig() throws IOException {
        EssentialsSettings s = bundled();
        assertEquals(Set.of(), s.disabledCommands());
        assertEquals(0, s.teleport().warmupSeconds());
        assertEquals(10, s.teleport().cooldown(TeleportKind.BACK));
        assertEquals(0, s.teleport().cooldown(TeleportKind.HOME));
        assertTrue(s.back().onDeath());
        assertEquals(60, s.tpa().expireSeconds());
        assertEquals(3, s.homes().limit());
        assertEquals(Set.of(), s.homes().blockedWorlds());
        assertEquals(new EssentialsSettings.Economy(10, 1), s.economy());
        assertEquals(Map.of("rules", List.of(), "store", List.of("buy"), "website", List.of("site")), s.info());
        assertEquals(List.of("rules", "store", "website"), List.copyOf(s.info().keySet()));
    }

    /** A server whose copy predates a key still starts, with the bundled default. */
    @Test
    void emptyConfigMatchesBundledDefaults() throws IOException {
        EssentialsSettings empty = EssentialsSettings.from(ConfigView.empty());
        EssentialsSettings bundled = bundled();
        assertEquals(bundled.back(), empty.back());
        assertEquals(bundled.tpa(), empty.tpa());
        assertEquals(bundled.homes(), empty.homes());
        assertEquals(bundled.economy(), empty.economy());
        assertEquals(bundled.teleport().warmupSeconds(), empty.teleport().warmupSeconds());
    }

    /** Frontier worlds get a new key every month, so a pattern blocks all of them. */
    @Test
    void blockedWorldPatterns() {
        EssentialsSettings s = EssentialsSettings.from(ConfigView.of(Map.of(
                "homes", Map.of("blocked-worlds", List.of("rapidskies:frontier_*", "Minecraft:The_End")))));
        assertTrue(s.homes().blocked("rapidskies:frontier_3"));
        assertTrue(s.homes().blocked("rapidskies:frontier_12_nether"));
        assertTrue(s.homes().blocked("minecraft:the_end"));
        assertEquals(false, s.homes().blocked("minecraft:overworld"));
        assertEquals(false, s.homes().blocked("rapidskies:spawn"));
        assertEquals(false, s.homes().blocked("minecraft:the_end_2"), "without a * the key must match exactly");
    }

    @Test
    void disabledCommandsAreNormalized() {
        EssentialsSettings s = EssentialsSettings.from(ConfigView.of(Map.of(
                "disabled-commands", List.of("/Home", " sethome "))));
        assertTrue(s.disabled("home"));
        assertTrue(s.disabled("sethome"));
        assertEquals(Set.of("home", "sethome"), s.disabledCommands());
    }

    @Test
    void everyMistakeIsReported() {
        Map<String, Object> root = Map.of(
                "teleport", Map.of("warmup-seconds", -1, "cooldown-seconds", Map.of("bakc", 5)),
                "tpa", Map.of("expire-seconds", 0),
                "homes", Map.of("limit", "lots"),
                "info-commands", Map.of("Bad Name", List.of()));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> EssentialsSettings.from(ConfigView.of(root)));
        String msg = e.getMessage();
        assertTrue(msg.contains("teleport.warmup-seconds"), msg);
        assertTrue(msg.contains("teleport.cooldown-seconds.bakc"), msg);
        assertTrue(msg.contains("tpa.expire-seconds"), msg);
        assertTrue(msg.contains("homes.limit"), msg);
        assertTrue(msg.contains("info-commands.Bad Name"), msg);
    }
}
