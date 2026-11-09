package us.rapidskies.common.lang;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.slf4j.Logger;
import us.rapidskies.common.RapidBrand;
import us.rapidskies.common.config.ConfigView;

/**
 * A plugin's message bundle (PLAN.md §2: no hard-coded strings in plugins).
 *
 * <p>Messages are MiniMessage strings in {@code lang/en_US.yml}, flattened to dotted keys.
 * The copy bundled in the jar is the default; a copy in the plugin's data folder overrides
 * individual keys, so staff can reword messages without a rebuild and new keys still
 * appear after an update.
 *
 * <p>Every message can use the brand tags {@code <primary> <accent> <text> <muted> <dark>
 * <success> <error>} and {@code <prefix>} (the "RAPIDSKIES »" chat prefix).
 */
public final class Lang {

    public static final String DEFAULT_RESOURCE = "lang/en_US.yml";

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final TagResolver BRAND = brandTags();

    private final Map<String, Object> messages;
    private final Logger logger;
    private final Set<String> reportedMissing = ConcurrentHashMap.newKeySet();

    private Lang(Map<String, Object> messages, Logger logger) {
        this.messages = Map.copyOf(messages);
        this.logger = logger;
    }

    /** Bundled defaults only (tests, and anything that runs before the data folder exists). */
    public static Lang fromResource(ClassLoader loader, String resource, Logger logger) throws IOException {
        return new Lang(readResource(loader, resource), logger);
    }

    /**
     * Bundled defaults overlaid with {@code override} (written from the defaults on first
     * start, so staff have a file to edit).
     */
    public static Lang load(ClassLoader loader, String resource, Path override, Logger logger) throws IOException {
        Map<String, Object> merged = readResource(loader, resource);
        if (Files.notExists(override)) {
            try (InputStream in = loader.getResourceAsStream(resource)) {
                if (in != null) {
                    Files.createDirectories(override.toAbsolutePath().getParent());
                    Files.copy(in, override);
                }
            }
        } else {
            Map<String, Object> custom = new LinkedHashMap<>();
            flatten("", ConfigView.load(override).asMap(), custom);
            merged.putAll(custom);
        }
        return new Lang(merged, logger);
    }

    private static Map<String, Object> readResource(ClassLoader loader, String resource) throws IOException {
        try (InputStream in = loader.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("missing bundled resource " + resource);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            flatten("", ConfigView.load(in).asMap(), out);
            return out;
        }
    }

    private static void flatten(String prefix, Map<?, ?> in, Map<String, Object> out) {
        in.forEach((k, v) -> {
            String key = prefix.isEmpty() ? String.valueOf(k) : prefix + "." + k;
            if (v instanceof Map<?, ?> nested) {
                flatten(key, nested, out);
            } else if (v instanceof List<?> list) {
                out.put(key, list.stream().map(String::valueOf).toList());
            } else if (v != null) {
                out.put(key, String.valueOf(v));
            }
        });
    }

    private static TagResolver brandTags() {
        return TagResolver.resolver(
                color("primary", RapidBrand.PRIMARY),
                color("accent", RapidBrand.ACCENT),
                color("text", RapidBrand.TEXT),
                color("muted", RapidBrand.MUTED),
                color("dark", RapidBrand.DARK),
                color("success", RapidBrand.SUCCESS),
                color("error", RapidBrand.ERROR),
                TagResolver.resolver("prefix", Tag.selfClosingInserting(MINI.deserialize(RapidBrand.PREFIX))));
    }

    private static TagResolver color(String name, String hex) {
        return TagResolver.resolver(name, Tag.styling(TextColor.fromHexString(hex)));
    }

    /** The brand tags, for code that parses MiniMessage outside a bundle. */
    public static TagResolver brand() {
        return BRAND;
    }

    /** Parses arbitrary MiniMessage with the brand tags available. */
    public static Component parse(String miniMessage, TagResolver... resolvers) {
        return MINI.deserialize(miniMessage, TagResolver.resolver(BRAND, TagResolver.resolver(resolvers)));
    }

    public boolean has(String key) {
        return messages.containsKey(key);
    }

    /** Every message key in the bundle (tests check each message renders). */
    public Set<String> keys() {
        return messages.keySet();
    }

    /** The unparsed MiniMessage string for {@code key}. A list is joined with newlines. */
    public String raw(String key) {
        Object v = messages.get(key);
        if (v == null) {
            if (reportedMissing.add(key) && logger != null) {
                logger.warn("Missing message key '{}' in the lang bundle", key);
            }
            return "<error>[missing: " + key + "]</error>";
        }
        if (v instanceof List<?> list) {
            return String.join("\n", list.stream().map(String::valueOf).toList());
        }
        return (String) v;
    }

    public Component get(String key, TagResolver... resolvers) {
        return parse(raw(key), resolvers);
    }

    /** Each entry of a list message as its own component (MOTD rotation, help pages). */
    public List<Component> list(String key, TagResolver... resolvers) {
        Object v = messages.get(key);
        List<String> lines = v instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of(raw(key));
        List<Component> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            out.add(parse(line, resolvers));
        }
        return out;
    }

    public void send(Audience audience, String key, TagResolver... resolvers) {
        audience.sendMessage(get(key, resolvers));
    }
}
