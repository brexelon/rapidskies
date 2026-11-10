package us.rapidskies.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
import us.rapidskies.proxy.party.Parties;

/**
 * Every message key the proxy code names exists in the bundled lang file, so a typo shows
 * up here instead of as "[missing: ...]" in front of a player.
 */
class LangKeysTest {

    private static final Pattern KEY = Pattern.compile(
            "\"((?:admin|bolts|errors|freeze|friend|grant|kick|maintenance|motd|msg|party|queue|staffchat)\\.[a-z0-9.\\-_]*[a-z0-9])\"");

    @Test
    void everyKeyInTheCodeIsInTheBundle() throws IOException {
        Lang lang = Lang.fromResource(getClass().getClassLoader(), Lang.DEFAULT_RESOURCE, null);
        Set<String> missing = new TreeSet<>();
        for (String key : keysInSources()) {
            if (!lang.has(key)) {
                missing.add(key);
            }
        }
        for (Parties.Failure f : Parties.Failure.values()) {
            String key = "party.fail." + f.name().toLowerCase(Locale.ROOT);
            if (!lang.has(key)) {
                missing.add(key);
            }
        }
        // GrantService#what: "grant.name." + group, for every grantable rank and team role.
        for (Rank r : Rank.values()) {
            if (r != Rank.DEFAULT && !lang.has("grant.name." + r.group())) {
                missing.add("grant.name." + r.group());
            }
        }
        for (StaffRole s : StaffRole.values()) {
            if (!lang.has("grant.name." + s.group())) {
                missing.add("grant.name." + s.group());
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
        assertTrue(Files.isDirectory(src), "run from the rapid-proxy module directory");
        Set<String> keys = new TreeSet<>();
        try (Stream<Path> files = Files.walk(src)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = KEY.matcher(Files.readString(f));
                while (m.find()) {
                    keys.add(m.group(1));
                }
            }
        }
        // Prefixes the code completes at runtime, and config paths that share a key's shape.
        keys.removeIf(k -> List.of("party.fail", "grant.name", "queue.groups", "queue.priority", "queue.priority.staff",
                "queue.return-after-restart-minutes", "motd.rotate-seconds").contains(k));
        return keys;
    }
}
