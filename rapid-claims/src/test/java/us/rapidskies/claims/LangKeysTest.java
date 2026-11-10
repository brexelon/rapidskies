package us.rapidskies.claims;

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
import org.junit.jupiter.api.Test;
import us.rapidskies.claims.model.Flag;
import us.rapidskies.claims.model.Role;
import us.rapidskies.claims.model.TownRole;
import us.rapidskies.claims.protect.Action;
import us.rapidskies.claims.town.Project;
import us.rapidskies.common.lang.Lang;

/** Every message key rapid-claims' code names exists in its bundled lang file, and its tags parse. */
class LangKeysTest {

    private static final Pattern KEY = Pattern.compile("\"((?:errors|owner|claim|buy|trust|deny|flags|flag|show|map|"
            + "menu|protect|entry|ruins|role|town|project|admin|placeholder|shop)\\.[a-z0-9.\\-]*[a-z0-9])\"");

    @Test
    void everyKeyInTheCodeIsInTheBundle() throws IOException {
        Lang lang = Lang.fromResource(getClass().getClassLoader(), Lang.DEFAULT_RESOURCE, null);
        Set<String> keys = keysInSources();
        // Keys built from enums ("role." + role.id(), ...).
        for (Role r : Role.values()) {
            keys.add("role." + r.id());
        }
        for (Flag f : Flag.values()) {
            keys.add("flag." + f.id() + ".name");
            keys.add("flag." + f.id() + ".lore");
        }
        for (Action a : Action.values()) {
            keys.add("protect.action." + a.id());
        }
        for (TownRole r : TownRole.values()) {
            keys.add("town.role." + r.id());
            keys.add("town.role-short." + r.id());
        }
        for (Project.Unlock.Kind k : Project.Unlock.Kind.values()) {
            keys.add("project.unlock." + new Project.Unlock(k, 1).id());
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

    /** Placeholders inside click commands are pre-processed, so the command comes out whole. */
    @Test
    void clickCommandsGetTheirArguments() throws IOException {
        Lang lang = Lang.fromResource(getClass().getClassLoader(), Lang.DEFAULT_RESOURCE, null);
        Component c = lang.get("town.invite-received",
                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.parsed("name", "Riverside"));
        List<String> commands = new ArrayList<>();
        collect(c, commands);
        assertTrue(commands.contains("/town join Riverside"), commands.toString());
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
        assertTrue(Files.isDirectory(src), "run from the rapid-claims module directory");
        Set<String> keys = new TreeSet<>();
        try (Stream<Path> files = Files.walk(src)) {
            // ClaimSettings reads config.yml paths (flags.defaults...), which share prefixes with lang keys.
            for (Path f : files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.getFileName().toString().equals("ClaimSettings.java")).toList()) {
                Matcher m = KEY.matcher(Files.readString(f));
                while (m.find()) {
                    keys.add(m.group(1));
                }
            }
        }
        return keys;
    }
}
