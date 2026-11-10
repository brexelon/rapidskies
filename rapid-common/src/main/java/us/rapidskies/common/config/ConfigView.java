package us.rapidskies.common.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Read-only view over a parsed YAML document, addressed with dotted paths
 * ({@code database.host}). Works the same on Paper and Velocity, which both ship SnakeYAML.
 */
public final class ConfigView {

    private final Map<String, Object> root;

    private ConfigView(Map<String, Object> root) {
        this.root = root;
    }

    public static ConfigView of(Map<String, Object> root) {
        return new ConfigView(Objects.requireNonNull(root));
    }

    public static ConfigView empty() {
        return new ConfigView(Map.of());
    }

    public static ConfigView load(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return new ConfigView(asMap(yaml().load(reader)));
        }
    }

    public static ConfigView load(InputStream in) {
        return new ConfigView(asMap(yaml().load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8))));
    }

    /**
     * Copies {@code resource} from the plugin jar to {@code target} unless it already exists,
     * then loads {@code target}, with anything it lacks taken from {@code resource}
     * ({@link #withDefaults}). This is how every rapid plugin writes its default config on
     * first start (rsdeploy patches it afterwards; see infra/README.md). The defaults are only
     * filled in memory: the file on disk is left as it is (rsdeploy adds them there).
     */
    public static ConfigView loadOrCreate(ClassLoader loader, String resource, Path target) throws IOException {
        Map<String, Object> defaults;
        try (InputStream in = loader.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("missing bundled resource " + resource);
            }
            byte[] bundled = in.readAllBytes();
            if (Files.notExists(target)) {
                Files.createDirectories(target.toAbsolutePath().getParent());
                Files.write(target, bundled);
            }
            defaults = asMap(yaml().load(new String(bundled, StandardCharsets.UTF_8)));
        }
        return new ConfigView(withDefaults(load(target).root, defaults));
    }

    /**
     * {@code live} with the keys it lacks taken from {@code defaults}, so a config written by
     * an older version still gets the settings and sections a newer one added. A key that's
     * present is kept as it is, even when empty or null, and lists are never merged.
     *
     * <p>Sections are filled key by key, except one whose default values are all sections
     * itself (entries keyed by id: NPCs, Chapters, worlds, courses). That's a collection the
     * admin owns, so it's only taken from the defaults when it's missing entirely: filling it
     * would bring back entries that were deleted on purpose. The same rule is in
     * infra/rsdeploy.py ({@code fill_defaults}), which adds these keys to the file itself.
     */
    public static Map<String, Object> withDefaults(Map<String, Object> live, Map<String, Object> defaults) {
        Map<String, Object> out = new LinkedHashMap<>(live);
        defaults.forEach((key, def) -> {
            if (!out.containsKey(key)) {
                out.put(key, def);
            } else if (out.get(key) instanceof Map<?, ?> section && def instanceof Map<?, ?> defSection
                    && !isCollection(defSection)) {
                out.put(key, withDefaults(asMap(section), asMap(defSection)));
            }
        });
        return out;
    }

    /** Whether a default section is a collection of entries: non-empty or not, every value is a section. */
    static boolean isCollection(Map<?, ?> section) {
        return section.values().stream().allMatch(v -> v instanceof Map<?, ?>);
    }

    private static Yaml yaml() {
        return new Yaml(new SafeConstructor(new LoaderOptions()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        if (o == null) {
            return Map.of();
        }
        if (!(o instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("expected a YAML mapping at the top level");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    /** The raw value at {@code path}, or null. */
    public Object get(String path) {
        Object current = root;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(part);
        }
        return current;
    }

    public boolean contains(String path) {
        return get(path) != null;
    }

    public String string(String path, String def) {
        Object v = get(path);
        return v == null ? def : String.valueOf(v);
    }

    public int integer(String path, int def) {
        Object v = get(path);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(path + ": expected a number, got '" + s + "'", e);
            }
        }
        return def;
    }

    public boolean bool(String path, boolean def) {
        Object v = get(path);
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof String s && !s.isBlank()) {
            return Boolean.parseBoolean(s.trim());
        }
        return def;
    }

    public List<String> stringList(String path) {
        Object v = get(path);
        if (v instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        if (v != null) {
            return List.of(String.valueOf(v));
        }
        return List.of();
    }

    public ConfigView section(String path) {
        return get(path) instanceof Map<?, ?> m ? new ConfigView(asMap(m)) : empty();
    }

    /** Top-level keys of this view, in file order. */
    public java.util.Set<String> keys() {
        return root.keySet();
    }

    /** The view as nested maps (for flattening, e.g. by the lang loader). */
    public Map<String, Object> asMap() {
        return root;
    }
}
