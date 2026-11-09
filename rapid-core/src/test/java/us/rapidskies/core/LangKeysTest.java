package us.rapidskies.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.KeybindComponent;
import net.kyori.adventure.text.TextComponent;
import org.junit.jupiter.api.Test;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.common.rank.Rank;
import us.rapidskies.common.rank.StaffRole;

/** Every message key rapid-core's code names exists in its bundled lang file. */
class LangKeysTest {

    private static final Pattern KEY = Pattern.compile(
            "\"((?:afk|bolts|chat|errors|login|plus|rank|rewards|staff|tailwind)\\.[a-z0-9.\\-_]*[a-z0-9])\"");

    @Test
    void everyKeyInTheCodeIsInTheBundle() throws IOException {
        Lang lang = Lang.fromResource(getClass().getClassLoader(), Lang.DEFAULT_RESOURCE, null);
        Set<String> keys = keysInSources();
        // Built at runtime from the enums.
        for (Rank r : Rank.values()) {
            if (r != Rank.DEFAULT) {
                keys.add("rank.prefix." + r.group());
            }
        }
        for (StaffRole s : StaffRole.values()) {
            keys.add("rank.staff." + s.group());
        }
        // Staff mode tool names and lore ("staff.tool." + key + "-name").
        for (String tool : new String[] {"random", "inspect", "freeze", "players", "vanish-on", "vanish-off", "exit"}) {
            keys.add("staff.tool." + tool + "-name");
            keys.add("staff.tool." + tool + "-lore");
        }
        Set<String> missing = new TreeSet<>();
        for (String key : keys) {
            if (!lang.has(key)) {
                missing.add(key);
            }
        }
        assertEquals(Set.of(), missing);
    }

    /**
     * Interactive tags really parse. MiniMessage prints a malformed tag as plain text instead
     * of failing, so a typo in {@code <hover:show_text:'...'>} or {@code <key:...>} would
     * otherwise only show up in front of players.
     */
    @Test
    void interactiveTagsRender() throws IOException {
        Lang lang = Lang.fromResource(getClass().getClassLoader(), Lang.DEFAULT_RESOURCE, null);
        List<String> problems = new ArrayList<>();
        for (String key : new TreeSet<>(lang.keys())) {
            String raw = lang.raw(key);
            Component c = lang.get(key);
            for (String tag : new String[] {"hover", "click", "key"}) {
                if (raw.contains("<" + tag + ":") && text(c).contains("<" + tag)) {
                    problems.add(key + ": <" + tag + "> printed as text");
                }
            }
            if (raw.contains("<hover:") && !any(c, x -> x.hoverEvent() != null)) {
                problems.add(key + ": no hover");
            }
            if (raw.contains("<click:") && !any(c, x -> x.clickEvent() != null)) {
                problems.add(key + ": no click");
            }
            if (raw.contains("<key:") && !any(c, x -> x instanceof KeybindComponent)) {
                problems.add(key + ": no keybind");
            }
        }
        assertEquals(List.of(), problems);
    }

    private static boolean any(Component c, Predicate<Component> test) {
        return test.test(c) || c.children().stream().anyMatch(child -> any(child, test));
    }

    private static String text(Component c) {
        StringBuilder sb = new StringBuilder(c instanceof TextComponent t ? t.content() : "");
        c.children().forEach(child -> sb.append(text(child)));
        return sb.toString();
    }

    private static Set<String> keysInSources() throws IOException {
        Path src = Path.of("src/main/java");
        assertTrue(Files.isDirectory(src), "run from the rapid-core module directory");
        Set<String> keys = new TreeSet<>();
        try (Stream<Path> files = Files.walk(src)) {
            // CoreSettings reads config.yml paths (afk.idle-seconds...), which share prefixes with lang keys.
            for (Path f : files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.getFileName().toString().equals("CoreSettings.java")).toList()) {
                Matcher m = KEY.matcher(Files.readString(f));
                while (m.find()) {
                    keys.add(m.group(1));
                }
            }
        }
        return keys;
    }
}
