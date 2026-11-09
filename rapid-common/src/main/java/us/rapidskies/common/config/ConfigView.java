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
     * then loads {@code target}. This is how every rapid plugin writes its default config on
     * first start (rsdeploy patches it afterwards; see infra/README.md).
     */
    public static ConfigView loadOrCreate(ClassLoader loader, String resource, Path target) throws IOException {
        if (Files.notExists(target)) {
            try (InputStream in = loader.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IOException("missing bundled resource " + resource);
                }
                Files.createDirectories(target.toAbsolutePath().getParent());
                Files.copy(in, target);
            }
        }
        return load(target);
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
