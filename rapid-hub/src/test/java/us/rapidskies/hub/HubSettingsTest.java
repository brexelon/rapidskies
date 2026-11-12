package us.rapidskies.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import us.rapidskies.common.config.ConfigView;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.core.world.SpawnPoint;
import us.rapidskies.hub.HubSettings.Mode;

/**
 * The bundled config, with the hub profile from infra/ patched over it the way rsdeploy
 * does, parses into what the hub build expects. A mistake there would otherwise only be a
 * log line on the live hub.
 */
class HubSettingsTest {

    private static final Path HUB_PATCH = Path.of("../infra/profiles/hub/plugins/rapid-hub/config.yml.patch");

    @Test
    void bundledDefaultsParse() throws IOException {
        HubSettings s = HubSettings.from(ConfigView.of(bundled()));
        assertEquals(List.of("skyrealms", "smp"), s.modes().stream().map(Mode::id).toList());
        assertTrue(s.modes().stream().allMatch(m -> m.npc().isEmpty()), "positions belong in the hub profile");
        assertTrue(s.doubleJump().enabled());
        assertEquals("rapidskies:parkour", s.parkourWorld());
        assertEquals(List.of("main"), s.courses().stream().map(c -> c.id()).toList());
        assertTrue(s.course("main").orElseThrow().entrance().isEmpty(), "positions belong in the hub profile");
        assertTrue(s.boards().isEmpty());
    }

    @Test
    void hubProfileParses() throws IOException {
        Map<String, Object> config = bundled();
        patch(config, load(HUB_PATCH));
        HubSettings s = HubSettings.from(ConfigView.of(config));

        Mode skyrealms = s.modes().get(0);
        Mode smp = s.modes().get(1);
        assertEquals("skyrealms", skyrealms.group());
        assertEquals("smp", smp.group());
        SpawnPoint left = skyrealms.npc().orElseThrow();
        SpawnPoint right = smp.npc().orElseThrow();
        // On the two bridgehead pedestals north of the spawn (0.5 80 0.5), mirrored across its
        // axis, each turned toward the spawn (south, the SkyRealms one a little east).
        assertEquals(79.0, left.y());
        assertEquals(left.y(), right.y());
        assertEquals(left.z(), right.z());
        assertTrue(left.z() < 0, "ahead of the spawn, which faces north (-z)");
        assertEquals(1.0, left.x() + right.x(), "mirrored across the spawn's x 0.5");
        assertEquals(-right.yaw(), left.yaw());
        assertTrue(left.yaw() < 0 && left.yaw() > -90, "facing south-east, back to the spawn");
        assertTrue(left.x() < right.x(), "SkyRealms on the left seen from the spawn (west is -x)");

        assertEquals("rapidskies:parkour", s.parkourWorld());
        var main = s.course("main").orElseThrow();
        assertEquals(150, main.length());
        assertEquals(2, main.checkpoints(), "checkpoints at 50 and 100");
        assertTrue(main.entrance().isPresent(), "the pressure plate behind the spawn terrace");
        assertEquals(2, s.boards().size());
        assertEquals(1, s.boards().stream().filter(HubSettings.Board::weekly).count());
    }

    @Test
    void everyModeHasANameAndDescription() throws IOException {
        Lang lang = Lang.fromResource(getClass().getClassLoader(), Lang.DEFAULT_RESOURCE, null);
        for (Mode mode : HubSettings.from(ConfigView.of(bundled())).modes()) {
            assertTrue(lang.has("modes." + mode.id() + ".name"), mode.id());
            assertTrue(lang.has("modes." + mode.id() + ".lore"), mode.id());
        }
    }

    @Test
    void mistakesAreReportedTogether() {
        Map<String, Object> config = new LinkedHashMap<>();
        patch(config, Map.of(
                "modes.a.group", "a", "modes.a.slot", 11,
                "modes.b.group", "b", "modes.b.slot", 11,
                "modes.c.slot", 40,
                "parkour.courses.main.entrance", "1, 2",
                "parkour.courses.short.length", 10,
                "leaderboards.x.location", "minecraft:overworld, 0, 90, 0",
                "leaderboards.x.course", "nope"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> HubSettings.from(ConfigView.of(config)));
        String m = e.getMessage();
        // Either mode can be the second one read (Map.of has no order).
        assertTrue(m.contains(".slot: 11 is already taken"), m);
        assertTrue(m.contains("modes.c.group: missing"), m);
        assertTrue(m.contains("modes.c.slot: expected 0 to 26"), m);
        assertTrue(m.contains("parkour.courses.main.entrance"), m);
        assertTrue(m.contains("parkour.courses.short.length: expected 30 to 1000"), m);
        assertTrue(m.contains("leaderboards.x.course: no parkour course 'nope'"), m);
    }

    @Test
    void parkourNeedsAWorldOfItsOwn() {
        Map<String, Object> config = new LinkedHashMap<>();
        patch(config, Map.of("parkour.world", "minecraft:overworld"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> HubSettings.from(ConfigView.of(config)));
        assertTrue(e.getMessage().contains("parkour.world"), e.getMessage());
    }

    @Test
    void checkpointsNeedSpace() {
        Map<String, Object> config = new LinkedHashMap<>();
        patch(config, Map.of("parkour.courses.main.checkpoint-every", 5));
        assertThrows(IllegalArgumentException.class, () -> HubSettings.from(ConfigView.of(config)));

        patch(config, Map.of("parkour.courses.main.checkpoint-every", 25, "parkour.courses.main.length", 100,
                "parkour.courses.main.entrance", "-6, 86, 2"));
        var course = HubSettings.from(ConfigView.of(config)).course("main").orElseThrow();
        assertEquals(3, course.checkpoints());
        assertEquals("-6, 86, 2", course.entrance().orElseThrow().format());
    }

    private Map<String, Object> bundled() throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            return new LinkedHashMap<>(ConfigView.load(in).asMap());
        }
    }

    private static Map<String, Object> load(Path file) throws IOException {
        assertTrue(Files.exists(file), "run from the rapid-hub module directory");
        try (Reader r = Files.newBufferedReader(file)) {
            Object o = new Yaml(new SafeConstructor(new LoaderOptions())).load(r);
            @SuppressWarnings("unchecked") Map<String, Object> m = o == null ? Map.of() : (Map<String, Object>) o;
            return m;
        }
    }

    /** rsdeploy's YAML patching: dotted keys, creating sections as needed. */
    @SuppressWarnings("unchecked")
    private static void patch(Map<String, Object> into, Map<String, Object> dotted) {
        dotted.forEach((key, value) -> {
            String[] parts = key.split("\\.");
            Map<String, Object> node = into;
            for (int i = 0; i < parts.length - 1; i++) {
                Object child = node.get(parts[i]);
                if (!(child instanceof Map<?, ?>)) {
                    child = new LinkedHashMap<String, Object>();
                    node.put(parts[i], child);
                } else if (!(child instanceof LinkedHashMap<?, ?>)) {
                    child = new LinkedHashMap<>((Map<String, Object>) child);
                    node.put(parts[i], child);
                }
                node = (Map<String, Object>) child;
            }
            node.put(parts[parts.length - 1], value);
        });
    }
}
