package us.rapidskies.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.proxy.party.Parties;

/**
 * Every message key the proxy code names exists in the bundled lang file, so a typo shows
 * up here instead of as "[missing: ...]" in front of a player.
 */
class LangKeysTest {

    private static final Pattern KEY = Pattern.compile(
            "\"((?:admin|bolts|errors|friend|kick|maintenance|motd|msg|party|queue|staffchat)\\.[a-z0-9.\\-_]*[a-z0-9])\"");

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
        assertEquals(Set.of(), missing);
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
        keys.removeIf(k -> List.of("party.fail", "queue.groups", "queue.priority", "queue.priority.staff",
                "motd.rotate-seconds").contains(k));
        return keys;
    }
}
