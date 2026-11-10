package us.rapidskies.horizons;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
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
import us.rapidskies.common.config.ConfigView;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.horizons.trade.Trade;

/** Every message key rapid-horizons' code names exists in its bundled lang file, and its tags parse. */
class LangKeysTest {

    private static final Pattern KEY = Pattern.compile(
            "\"((?:admin|chapter|dimension|errors|frontier|npc|pvp|rewards|skyborne|trades|travel|wildlands)\\.[a-z0-9.\\-_]*[a-z0-9])\"");

    @Test
    void everyKeyInTheCodeIsInTheBundle() throws IOException {
        Lang lang = Lang.fromResource(getClass().getClassLoader(), Lang.DEFAULT_RESOURCE, null);
        Set<String> keys = keysInSources();
        // Built from the Trade ids ("trades.name." + trade.id()).
        for (Trade trade : Trade.values()) {
            keys.add("trades.name." + trade.id());
            keys.add("trades.about." + trade.id());
            keys.add("trades.perk." + trade.id());
        }
        for (us.rapidskies.horizons.frontier.Modifier m : us.rapidskies.horizons.frontier.Modifier.values()) {
            keys.add("frontier.modifier." + m.id() + ".name");
            keys.add("frontier.modifier." + m.id() + ".about");
        }
        // Built from the NPC ids in the bundled config ("npc." + id + ".name").
        HorizonsSettings bundled;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            bundled = HorizonsSettings.from(ConfigView.load(in));
        }
        assertFalse(bundled.npcs().isEmpty(), "the bundled config lists the spawn NPCs");
        for (HorizonsSettings.Npc npc : bundled.npcs()) {
            keys.add("npc." + npc.id() + ".name");
            keys.add("npc." + npc.id() + ".hint");
        }
        // Built from the chapter and entry ids ("chapter." + chapter.id() + ".name").
        assertFalse(bundled.chapters().isEmpty(), "the bundled config has Chapter 1");
        for (HorizonsSettings.Chapter chapter : bundled.chapters()) {
            String base = "chapter." + chapter.id();
            keys.add(base + ".name");
            keys.add(base + ".lore");
            keys.add(base + ".reward");
            for (HorizonsSettings.ChapterEntry entry : chapter.entries()) {
                keys.add(base + ".entries." + entry.id() + ".name");
                keys.add(base + ".entries." + entry.id() + ".hint");
            }
        }
        Set<String> missing = new TreeSet<>();
        for (String key : keys) {
            if (!lang.has(key)) {
                missing.add(key);
            }
        }
        assertEquals(Set.of(), missing);
    }

    /** MiniMessage prints a malformed tag as text instead of failing; catch that here. */
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
        assertTrue(Files.isDirectory(src), "run from the rapid-horizons module directory");
        Set<String> keys = new TreeSet<>();
        try (Stream<Path> files = Files.walk(src)) {
            // HorizonsSettings reads config.yml paths (trades.max-active, pvp.combat-tag-seconds),
            // which share prefixes with lang keys.
            for (Path f : files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.getFileName().toString().equals("HorizonsSettings.java")).toList()) {
                Matcher m = KEY.matcher(Files.readString(f));
                while (m.find()) {
                    keys.add(m.group(1));
                }
            }
        }
        return keys;
    }
}
