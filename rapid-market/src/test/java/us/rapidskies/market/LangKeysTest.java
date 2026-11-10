package us.rapidskies.market;

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
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.junit.jupiter.api.Test;
import us.rapidskies.common.economy.EconomyMode;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.market.model.Category;
import us.rapidskies.market.model.Notice;
import us.rapidskies.market.model.Sort;
import us.rapidskies.market.model.Stored;

/** Every message key rapid-market's code names exists in its bundled lang file, and its tags parse. */
class LangKeysTest {

    private static final Pattern KEY = Pattern.compile("\"((?:errors|format|mode|help|sell|buy|cancel|collect|notice|"
            + "alert|search|listing|category|sort|price|admin|menu)(?:\\.[a-z0-9\\-]*[a-z0-9])+)\"");

    @Test
    void everyKeyInTheCodeIsInTheBundle() throws IOException {
        Lang lang = Lang.fromResource(getClass().getClassLoader(), Lang.DEFAULT_RESOURCE, null);
        Set<String> keys = keysInSources();
        // Keys built from enums and ids ("category." + c.id(), ...).
        for (Category c : Category.values()) {
            keys.add("category." + c.id());
        }
        for (Sort s : Sort.values()) {
            keys.add("sort." + s.id());
        }
        for (Stored.Reason r : Stored.Reason.values()) {
            keys.add("collect.reason." + r.id());
        }
        for (Notice.Kind k : Notice.Kind.values()) {
            keys.add("notice." + k.id());
        }
        for (EconomyMode m : EconomyMode.values()) {
            keys.add("mode." + m.id());
        }
        for (String confirm : List.of("menu.buy.confirm", "menu.cancel.confirm", "menu.admin.confirm")) {
            keys.add(confirm + ".name");
            keys.add(confirm + ".lore");
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
            for (String tag : new String[] {"hover", "click"}) {
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
        }
        assertEquals(List.of(), problems);
    }

    /** The history footer's click command gets the player and page filled in. */
    @Test
    void historyFooterRunsTheNextPage() throws IOException {
        Lang lang = Lang.fromResource(getClass().getClassLoader(), Lang.DEFAULT_RESOURCE, null);
        Component c = lang.get("admin.history.footer", Placeholder.parsed("player", "Steve"),
                Placeholder.parsed("next", "3"));
        List<String> commands = new ArrayList<>();
        collect(c, commands);
        assertTrue(commands.contains("/ah admin history Steve 3"), commands.toString());
    }

    private static void collect(Component c, List<String> out) {
        ClickEvent<?> e = c.clickEvent();
        if (e != null && e.payload() instanceof ClickEvent.Payload.Text t) {
            out.add(t.value());
        }
        c.children().forEach(child -> collect(child, out));
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
        assertTrue(Files.isDirectory(src), "run from the rapid-market module directory");
        Set<String> keys = new TreeSet<>();
        try (Stream<Path> files = Files.walk(src)) {
            // MarketSettings reads config.yml paths (listing..., fees...), which share prefixes with lang keys.
            for (Path f : files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.getFileName().toString().equals("MarketSettings.java")).toList()) {
                Matcher m = KEY.matcher(Files.readString(f));
                while (m.find()) {
                    keys.add(m.group(1));
                }
            }
        }
        // Prefixes the code completes itself ("collect.reason." + ..., confirmKey + ".name").
        keys.add("help");
        keys.removeIf(k -> k.startsWith("menu.") && (k.endsWith(".confirm")));
        return keys;
    }
}
