package us.rapidskies.core.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import us.rapidskies.common.config.ConfigView;
import us.rapidskies.core.world.WorldConfig.Generator;
import us.rapidskies.core.world.WorldConfig.JoinSpawn;

class WorldConfigTest {

    private static ConfigView yaml(String text) {
        return ConfigView.load(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void parsesSpawnPoints() {
        SpawnPoint p = SpawnPoint.parse("minecraft:overworld, -5.5, 85, -2.5, 0, 0");
        assertEquals(new SpawnPoint("minecraft:overworld", -5.5, 85, -2.5, 0f, 0f), p);
        SpawnPoint noAngles = SpawnPoint.parse(" rapidskies:spawn ,42.5,-54,42.5");
        assertEquals("rapidskies:spawn", noAngles.world());
        assertEquals(0f, noAngles.yaw());
        assertThrows(IllegalArgumentException.class, () -> SpawnPoint.parse("hub, 1, 2"));
        assertThrows(IllegalArgumentException.class, () -> SpawnPoint.parse("hub, 1, two, 3"));
        assertThrows(IllegalArgumentException.class, () -> SpawnPoint.parse(" , 1, 2, 3"));
    }

    @Test
    void formatRoundsAndRoundTrips() {
        SpawnPoint p = new SpawnPoint("minecraft:overworld", 28.5, 150.0, -30.4999, 45.04f, -0.01f);
        assertEquals("minecraft:overworld, 28.5, 150, -30.5, 45, 0", p.format());
        assertEquals(p.format(), SpawnPoint.parse(p.format()).format());
    }

    @Test
    void defaultsWhenEmpty() {
        WorldConfig c = WorldConfig.from(ConfigView.empty());
        assertEquals(Optional.empty(), c.spawn());
        assertEquals(JoinSpawn.FIRST_JOIN, c.onJoin());
        assertTrue(c.respawnAtSpawn());
        assertTrue(c.worlds().isEmpty());
    }

    @Test
    void parsesWorlds() {
        WorldConfig c = WorldConfig.from(yaml("""
                spawn:
                  location: "rapidskies:spawn, 42.5, -54, 42.5, 0, 0"
                  on-join: always
                  on-death: false
                worlds:
                  "minecraft:overworld":
                    protect: true
                    void-y: 60
                    time: 6000
                    clear-weather: true
                    game-rules:
                      advance_time: false
                      random_tick_speed: 0
                  "rapidskies:spawn":
                    create: flat
                    border: { center-x: 48, center-z: 48, size: 96 }
                """));
        assertEquals(JoinSpawn.ALWAYS, c.onJoin());
        assertFalse(c.respawnAtSpawn());
        assertEquals(-54, c.spawn().orElseThrow().y());

        var hub = c.world("minecraft:overworld").orElseThrow();
        assertTrue(hub.protect());
        assertEquals(OptionalInt.of(60), hub.voidY());
        assertEquals(6000, hub.time().orElseThrow());
        assertTrue(hub.clearWeather());
        assertEquals(Optional.empty(), hub.create());
        assertEquals(Map.of("advance_time", "false", "random_tick_speed", "0"), hub.gameRules());

        var spawn = c.world("rapidskies:spawn").orElseThrow();
        assertEquals(Optional.of(Generator.FLAT), spawn.create());
        assertFalse(spawn.protect());
        assertEquals(new WorldConfig.Border(48, 48, 96), spawn.border().orElseThrow());
        assertTrue(spawn.voidY().isEmpty());
    }

    @Test
    void rejectsTypos() {
        assertThrows(IllegalArgumentException.class,
                () -> WorldConfig.from(yaml("spawn: { on-join: sometimes }")));
        assertThrows(IllegalArgumentException.class,
                () -> WorldConfig.from(yaml("worlds: { \"a:b\": { create: hills } }")));
        assertThrows(IllegalArgumentException.class,
                () -> WorldConfig.from(yaml("worlds: { \"a:b\": { border: { size: -5 } } }")));
    }

    @Test
    void generatorsAreSuperflatJsonExceptNormal() {
        assertEquals(null, Generator.NORMAL.flatSettings);
        assertTrue(Generator.VOID.flatSettings.contains("\"minecraft:the_void\""));
        // The void preset's biome would add a stone platform at 0,0 if features were on.
        assertTrue(Generator.VOID.flatSettings.contains("\"features\":false"));
        assertTrue(Generator.FLAT.flatSettings.contains("\"minecraft:grass_block\""));
    }
}
