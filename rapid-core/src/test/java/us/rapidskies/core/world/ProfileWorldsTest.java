package us.rapidskies.core.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import us.rapidskies.common.config.ConfigView;

/**
 * The spawn and world settings in infra/profiles parse, and every game rule they name exists
 * in Paper 26.3. A typo there would otherwise only be a warning in a server log.
 */
class ProfileWorldsTest {

    private static final Path PROFILES = Path.of("../infra/profiles");

    @Test
    void profileWorldSettingsAreValid() throws Exception {
        Set<String> rules = gameRuleKeys();
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (Path patch : patches()) {
            WorldConfig config = WorldConfig.from(ConfigView.of(expand(load(patch))));
            String where = PROFILES.relativize(patch).toString();
            config.spawn().ifPresent(spawn -> {
                boolean known = spawn.world().equals("minecraft:overworld")
                        || config.world(spawn.world()).map(w -> w.create().isPresent()).orElse(false);
                if (!known) {
                    problems.add(where + ": spawn world " + spawn.world()
                            + " is neither the main world nor created under `worlds`");
                }
            });
            for (WorldConfig.WorldSettings w : config.worlds()) {
                checked++;
                for (String rule : w.gameRules().keySet()) {
                    if (!rules.contains(rule.toLowerCase(Locale.ROOT).replace("minecraft:", ""))) {
                        problems.add(where + ": " + w.key() + ": no game rule '" + rule + "' in Paper 26.3");
                    }
                }
            }
        }
        assertEquals(List.of(), problems);
        assertTrue(checked >= 3, "expected the hub, skyrealms and smp profiles to configure worlds");
    }

    /** 26.x game rule keys, from org.bukkit.GameRules' constants, without initializing the class. */
    private static Set<String> gameRuleKeys() throws ClassNotFoundException {
        Class<?> c = Class.forName("org.bukkit.GameRules", false, ProfileWorldsTest.class.getClassLoader());
        Set<String> keys = Arrays.stream(c.getDeclaredFields())
                .filter(f -> Modifier.isStatic(f.getModifiers()) && Modifier.isPublic(f.getModifiers()))
                .map(Field::getName)
                .map(n -> n.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        assertFalse(keys.isEmpty());
        return keys;
    }

    private static List<Path> patches() throws IOException {
        assertTrue(Files.isDirectory(PROFILES), "run from the rapid-core module directory");
        try (Stream<Path> files = Files.walk(PROFILES)) {
            return files.filter(p -> p.endsWith(Path.of("plugins", "rapid-core", "config.yml.patch"))).sorted().toList();
        }
    }

    private static Map<String, Object> load(Path file) throws IOException {
        try (Reader r = Files.newBufferedReader(file)) {
            Object o = new Yaml(new SafeConstructor(new LoaderOptions())).load(r);
            @SuppressWarnings("unchecked") Map<String, Object> m = o == null ? Map.of() : (Map<String, Object>) o;
            return m;
        }
    }

    /** rsdeploy patches use dotted keys ("spawn.location"); nest them like the live config. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> expand(Map<String, Object> flat) {
        Map<String, Object> root = new LinkedHashMap<>();
        flat.forEach((key, value) -> {
            String[] parts = key.split("\\.");
            Map<String, Object> node = root;
            for (int i = 0; i < parts.length - 1; i++) {
                node = (Map<String, Object>) node.computeIfAbsent(parts[i], k -> new LinkedHashMap<String, Object>());
            }
            node.put(parts[parts.length - 1], value);
        });
        return root;
    }
}
