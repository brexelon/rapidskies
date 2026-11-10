package us.rapidskies.essentials;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import us.rapidskies.common.config.ConfigView;
import us.rapidskies.essentials.teleport.TeleportKind;

/**
 * rapid-essentials' config.yml, checked as a whole: a mistake anywhere is reported with
 * every other mistake, and nothing starts with half a config.
 *
 * @param disabledCommands command labels this server doesn't register (read once, at startup)
 * @param info             the text commands ({@code /rules}, {@code /store}) by id, with their aliases
 */
public record EssentialsSettings(Set<String> disabledCommands, Teleport teleport, Back back, Tpa tpa, Homes homes,
                                 Economy economy, Map<String, List<String>> info) {

    private static final Pattern LABEL = Pattern.compile("[a-z0-9_-]+");

    /**
     * @param warmupSeconds standing still this long before a teleport happens (0: at once)
     * @param cooldownSeconds after a teleport of a kind, how long before the next one of that kind
     */
    public record Teleport(int warmupSeconds, Map<TeleportKind, Integer> cooldownSeconds) {
        public int cooldown(TeleportKind kind) {
            return cooldownSeconds.getOrDefault(kind, 0);
        }
    }

    /** {@code /back} also returns to where you died. */
    public record Back(boolean onDeath) {
    }

    /** How long a {@code /tpa} or {@code /tpahere} request stays open. */
    public record Tpa(int expireSeconds) {
    }

    /**
     * @param limit         homes per player, the same for everyone (PLAN.md §6.1: never sold)
     * @param blockedWorlds world keys where homes can't be set (rotating Frontiers); one ending
     *                      in {@code *} matches every key starting with the rest
     *                      ({@code rapidskies:frontier_*})
     */
    public record Homes(int limit, Set<String> blockedWorlds) {

        /** Whether homes can't be set in the world with this key. */
        public boolean blocked(String worldKey) {
            String key = worldKey.toLowerCase(Locale.ROOT);
            for (String pattern : blockedWorlds) {
                if (pattern.endsWith("*") ? key.startsWith(pattern.substring(0, pattern.length() - 1))
                        : key.equals(pattern)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** {@code /baltop} length and the smallest {@code /pay}. */
    public record Economy(int baltopSize, long payMinimum) {
    }

    public boolean disabled(String label) {
        return disabledCommands.contains(label);
    }

    public static EssentialsSettings from(ConfigView c) {
        List<String> problems = new ArrayList<>();

        Set<String> disabled = new LinkedHashSet<>();
        for (String label : c.stringList("disabled-commands")) {
            disabled.add(label.trim().toLowerCase(Locale.ROOT).replaceFirst("^/", ""));
        }

        int warmup = nonNegative(c, "teleport.warmup-seconds", 0, problems);
        Map<TeleportKind, Integer> cooldowns = new EnumMap<>(TeleportKind.class);
        ConfigView cooldownSection = c.section("teleport.cooldown-seconds");
        for (String key : cooldownSection.keys()) {
            TeleportKind kind = TeleportKind.parse(key);
            if (kind == null) {
                problems.add("teleport.cooldown-seconds." + key + ": not a teleport kind (back, home, warp, spawn, tpa)");
                continue;
            }
            cooldowns.put(kind, nonNegative(c, "teleport.cooldown-seconds." + key, 0, problems));
        }

        Back back = new Back(c.bool("back.on-death", true));
        Tpa tpa = new Tpa(positive(c, "tpa.expire-seconds", 60, problems));

        Set<String> blocked = new LinkedHashSet<>();
        for (String world : c.stringList("homes.blocked-worlds")) {
            blocked.add(world.trim().toLowerCase(Locale.ROOT));
        }
        Homes homes = new Homes(nonNegative(c, "homes.limit", 3, problems), Set.copyOf(blocked));

        Economy economy = new Economy(positive(c, "economy.baltop-size", 10, problems),
                positive(c, "economy.pay-minimum", 1, problems));

        Map<String, List<String>> info = new LinkedHashMap<>();
        ConfigView infoSection = c.section("info-commands");
        for (String id : infoSection.keys()) {
            String label = id.toLowerCase(Locale.ROOT);
            if (!LABEL.matcher(label).matches()) {
                problems.add("info-commands." + id + ": a command name is lowercase letters, digits, - and _");
                continue;
            }
            List<String> aliases = new ArrayList<>();
            for (String alias : infoSection.stringList(id)) {
                String a = alias.trim().toLowerCase(Locale.ROOT).replaceFirst("^/", "");
                if (!LABEL.matcher(a).matches()) {
                    problems.add("info-commands." + id + ": bad alias '" + alias + "'");
                } else {
                    aliases.add(a);
                }
            }
            info.put(label, List.copyOf(aliases));
        }

        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("config.yml: " + String.join("; ", problems));
        }
        return new EssentialsSettings(Set.copyOf(disabled), new Teleport(warmup, Map.copyOf(cooldowns)), back, tpa,
                homes, economy, java.util.Collections.unmodifiableMap(info));
    }

    private static int nonNegative(ConfigView c, String path, int def, List<String> problems) {
        int v = number(c, path, def, problems);
        if (v < 0) {
            problems.add(path + ": must be 0 or more, got " + v);
            return def;
        }
        return v;
    }

    private static int positive(ConfigView c, String path, int def, List<String> problems) {
        int v = number(c, path, def, problems);
        if (v < 1) {
            problems.add(path + ": must be 1 or more, got " + v);
            return def;
        }
        return v;
    }

    private static int number(ConfigView c, String path, int def, List<String> problems) {
        try {
            return c.integer(path, def);
        } catch (IllegalArgumentException e) {
            problems.add(e.getMessage());
            return def;
        }
    }
}
