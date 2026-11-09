package us.rapidskies.core.world;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import us.rapidskies.common.config.ConfigView;

/**
 * The {@code spawn} and {@code worlds} sections of rapid-core's config: where players appear
 * on this server, and how rapid-core looks after its spawn worlds (the hub, the Skyrealms
 * Trading Post, the Horizons spawn). The profiles in infra/ fill these in per server.
 */
public record WorldConfig(Optional<SpawnPoint> spawn, JoinSpawn onJoin, boolean respawnAtSpawn,
                          List<WorldSettings> worlds) {

    /** When a joining player is put at the spawn. */
    public enum JoinSpawn {
        /** Every join (the hub). */
        ALWAYS,
        /** Only a player's first join on this server; afterwards they log in where they left. */
        FIRST_JOIN,
        /** Never: vanilla decides. */
        NEVER;

        static JoinSpawn parse(String s) {
            return switch (s.trim().toLowerCase(Locale.ROOT).replace('_', '-')) {
                case "always" -> ALWAYS;
                case "first-join", "first" -> FIRST_JOIN;
                case "never", "" -> NEVER;
                default -> throw new IllegalArgumentException(
                        "spawn.on-join: expected always, first-join or never, got '" + s + "'");
            };
        }
    }

    /** How a world rapid-core loads itself is generated outside the chunks it ships with. */
    public enum Generator {
        /** Vanilla terrain. */
        NORMAL(null),
        /** Classic superflat: bedrock, two dirt, grass, plains. Matches ground-level builds. */
        FLAT("{\"layers\":[{\"block\":\"minecraft:bedrock\",\"height\":1},"
                + "{\"block\":\"minecraft:dirt\",\"height\":2},{\"block\":\"minecraft:grass_block\",\"height\":1}],"
                + "\"biome\":\"minecraft:plains\",\"features\":false,\"lakes\":false,\"structure_overrides\":[]}"),
        /** Nothing but air (vanilla's "The Void" preset without its stone platform). For sky islands. */
        VOID("{\"layers\":[{\"block\":\"minecraft:air\",\"height\":1}],"
                + "\"biome\":\"minecraft:the_void\",\"features\":false,\"lakes\":false,\"structure_overrides\":[]}");

        /** Superflat generator settings JSON, or null for vanilla terrain. */
        public final String flatSettings;

        Generator(String flatSettings) {
            this.flatSettings = flatSettings;
        }

        static Generator parse(String path, String s) {
            try {
                return valueOf(s.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(path + ": expected normal, flat or void, got '" + s + "'", e);
            }
        }
    }

    /** A square world border. */
    public record Border(double centerX, double centerZ, double size) {
    }

    /**
     * One world rapid-core looks after.
     *
     * @param key        world key, e.g. {@code minecraft:overworld} (the main world) or {@code rapidskies:spawn}
     * @param create     load the world at startup (creating it if it's missing) with this generator;
     *                   empty for worlds the server loads by itself (the main world)
     * @param protect    only players with {@code rapid.world.build} may change anything
     * @param voidY      players below this height are sent back to the spawn instead of falling into the void
     * @param time       fixed time of day in ticks, set at startup (pair with {@code advance_time: false})
     * @param gameRules  game rule key (26.x names, e.g. {@code advance_time}) to value
     */
    public record WorldSettings(String key, Optional<Generator> create, boolean protect, OptionalInt voidY,
                                Optional<Border> border, OptionalLong time, boolean clearWeather,
                                Map<String, String> gameRules) {
    }

    public static WorldConfig from(ConfigView c) {
        String location = c.string("spawn.location", "").trim();
        Optional<SpawnPoint> spawn = location.isEmpty() ? Optional.empty() : Optional.of(SpawnPoint.parse(location));
        JoinSpawn onJoin = JoinSpawn.parse(c.string("spawn.on-join", "first-join"));
        boolean onDeath = c.bool("spawn.on-death", true);

        List<WorldSettings> worlds = new ArrayList<>();
        ConfigView all = c.section("worlds");
        for (String key : all.keys()) {
            // Keys are read as whole map entries: a world key may not contain dots anyway, but
            // ConfigView paths would split on them.
            Object raw = all.asMap().get(key);
            ConfigView w = raw instanceof Map<?, ?> m ? ConfigView.of(stringKeys(m)) : ConfigView.empty();
            String path = "worlds." + key;
            String create = w.string("create", "").trim();
            OptionalInt voidY = w.contains("void-y") ? OptionalInt.of(w.integer("void-y", 0)) : OptionalInt.empty();
            Optional<Border> border = Optional.empty();
            if (w.contains("border.size")) {
                double size = number(w, "border.size");
                if (size <= 0) {
                    throw new IllegalArgumentException(path + ".border.size must be positive");
                }
                border = Optional.of(new Border(number(w, "border.center-x"), number(w, "border.center-z"), size));
            }
            OptionalLong time = w.contains("time") ? OptionalLong.of(w.integer("time", 0)) : OptionalLong.empty();
            Map<String, String> rules = new LinkedHashMap<>();
            w.section("game-rules").asMap().forEach((k, v) -> rules.put(k, String.valueOf(v)));
            worlds.add(new WorldSettings(key.trim(),
                    create.isEmpty() ? Optional.empty() : Optional.of(Generator.parse(path + ".create", create)),
                    w.bool("protect", false), voidY, border, time, w.bool("clear-weather", false),
                    Map.copyOf(rules)));
        }
        return new WorldConfig(spawn, onJoin, onDeath, List.copyOf(worlds));
    }

    /** Settings for {@code key}, if rapid-core looks after that world. */
    public Optional<WorldSettings> world(String key) {
        return worlds.stream().filter(w -> w.key().equals(key)).findFirst();
    }

    private static double number(ConfigView c, String path) {
        Object v = c.get(path);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String s && !s.isBlank()) {
            return Double.parseDouble(s.trim());
        }
        return 0;
    }

    private static Map<String, Object> stringKeys(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }
}
