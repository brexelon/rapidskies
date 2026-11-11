package us.rapidskies.skyrealms;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import net.kyori.adventure.text.TextComponent;
import org.junit.jupiter.api.Test;
import us.rapidskies.common.config.ConfigView;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.skyrealms.expedition.Theme;
import us.rapidskies.skyrealms.tier.TierGoal;

/** Every message key rapid-skyrealms' code names exists in its bundled lang file, and its tags parse. */
class LangKeysTest {

    private static final Pattern KEY = Pattern.compile(
            "\"((?:admin|errors|npc|realm|shop|route|expedition|season|placeholder)\\.[a-z0-9.\\-_]*[a-z0-9])\"");

    @Test
    void everyKeyInTheCodeIsInTheBundle() throws IOException {
        Lang lang = Lang.fromResource(getClass().getClassLoader(), Lang.DEFAULT_RESOURCE, null);
        Set<String> keys = keysInSources();
        SkyRealmsSettings bundled;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            bundled = SkyRealmsSettings.from(ConfigView.load(in));
        }
        // Built from ids: tiers, biomes, goal types, shop categories, themes, route outcomes.
        for (SkyRealmsSettings.Tier t : bundled.tiers()) {
            keys.add("realm.tier." + t.id() + ".name");
            keys.add("realm.tier." + t.id() + ".about");
            keys.add("realm.biome." + t.biome());
        }
        for (TierGoal.Type type : TierGoal.Type.values()) {
            keys.add("realm.goal." + type.id());
        }
        for (String state : List.of("reached", "current", "locked")) {
            keys.add("realm.menu.state-" + state);
        }
        for (SkyRealmsSettings.Category c : bundled.shop().categories()) {
            keys.add("shop.category." + c.id());
        }
        for (Theme theme : Theme.values()) {
            keys.add("expedition.theme." + theme.id() + ".name");
            keys.add("expedition.theme." + theme.id() + ".about");
            keys.add("expedition.theme." + theme.id() + ".heart");
        }
        for (String reason : List.of("declined", "cancelled", "expired", "missed", "disbanded", "staff")) {
            keys.add("route.ended-" + reason);
        }
        for (String why : List.of("no-chest", "short", "unpaid")) {
            keys.add("route.missed-" + why);
        }
        assertTrue(bundled.npcs().size() >= 2, "the bundled config lists the Trading Post NPCs");
        for (SkyRealmsSettings.Npc npc : bundled.npcs()) {
            keys.add("npc." + npc.id() + ".name");
            keys.add("npc." + npc.id() + ".hint");
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
        assertTrue(Files.isDirectory(src), "run from the rapid-skyrealms module directory");
        Set<String> keys = new TreeSet<>();
        try (Stream<Path> files = Files.walk(src)) {
            // SkyRealmsSettings reads config.yml paths (shop.categories, routes...), which share
            // prefixes with lang keys.
            for (Path f : files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.getFileName().toString().equals("SkyRealmsSettings.java")).toList()) {
                Matcher m = KEY.matcher(Files.readString(f));
                while (m.find()) {
                    keys.add(m.group(1));
                }
            }
        }
        return keys;
    }
}
