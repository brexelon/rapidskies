package us.rapidskies.core.quest;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import us.rapidskies.common.config.ConfigView;
import us.rapidskies.core.cosmetic.Catalog;

/**
 * {@code quests.yml} (PLAN.md §7): the daily and weekly pools, their slots and rewards, and
 * the achievements. Read from the rapid-core jar so every server assigns the same quests.
 */
public record QuestConfig(List<String> dailySlots, int bankDays, Reward dailyReward, List<String> weeklySlots,
                          Reward weeklyReward, Map<String, Quest> daily, Map<String, Quest> weekly,
                          Map<String, Achievement> achievements) {

    public static final String RESOURCE = "quests.yml";

    /** What rapid-core counts itself. */
    public static final Set<String> CORE_OBJECTIVES =
            Set.of("break-blocks", "harvest-crops", "kill-mobs", "catch-fish", "play-minutes");
    /** What the gamemode plugins count ({@code RapidCore#quests().record}). */
    public static final Set<String> PLUGIN_OBJECTIVES =
            Set.of("parkour", "expedition", "shop-sell", "skyborne", "ah-buy");
    /** Lifetime stat only: quests finished. */
    public static final String QUESTS_STAT = "quests";

    public static final Set<String> MODES = Set.of("any", "hub", "horizons", "skyrealms");

    public record Reward(long bolts, long xp) {
    }

    public record Quest(String id, String kind, String mode, String objective, long target, Set<Material> blocks,
                        Set<EntityType> mobs, String name, String icon) {

        /** Whether this quest counts on a server running {@code serverMode}. */
        public boolean countsOn(String serverMode) {
            return mode.equals("any") || mode.equals(serverMode);
        }

        /** Whether it fills a slot of {@code slotMode} ({@code any} takes any and hub quests). */
        public boolean fits(String slotMode) {
            return slotMode.equals("any") ? mode.equals("any") || mode.equals("hub") : mode.equals(slotMode);
        }

        public boolean matches(String objective, Material block, EntityType mob) {
            if (!this.objective.equals(objective)) {
                return false;
            }
            if (!blocks.isEmpty() && (block == null || !blocks.contains(block))) {
                return false;
            }
            return mobs.isEmpty() || (mob != null && mobs.contains(mob));
        }
    }

    public record Achievement(String id, String stat, long target, String name, String description, long bolts,
                              long xp, String cosmetic, String icon) {
    }

    public static QuestConfig load(ClassLoader loader, Catalog catalog) throws IOException {
        try (InputStream in = loader.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IOException("missing bundled resource " + RESOURCE);
            }
            return parse(ConfigView.load(in), catalog);
        }
    }

    public static QuestConfig parse(ConfigView c, Catalog catalog) {
        List<String> problems = new ArrayList<>();
        List<String> dailySlots = slots(c.stringList("daily.slots"), "daily.slots", problems);
        List<String> weeklySlots = slots(c.stringList("weekly.slots"), "weekly.slots", problems);
        Map<String, Quest> daily = pool(c.section("quests.daily"), "daily", problems);
        Map<String, Quest> weekly = pool(c.section("quests.weekly"), "weekly", problems);
        checkSlots(dailySlots, daily, "daily", problems);
        checkSlots(weeklySlots, weekly, "weekly", problems);

        Map<String, Achievement> achievements = new LinkedHashMap<>();
        ConfigView ach = c.section("achievements");
        for (String id : ach.keys()) {
            ConfigView a = ach.section(id);
            String where = "achievements." + id;
            String stat = a.string("stat", "");
            if (!CORE_OBJECTIVES.contains(stat) && !PLUGIN_OBJECTIVES.contains(stat) && !stat.equals(QUESTS_STAT)) {
                problems.add(where + ": unknown stat '" + stat + "'");
            }
            long target = a.integer("target", 0);
            if (target <= 0) {
                problems.add(where + ": target must be positive");
            }
            String cosmetic = a.string("cosmetic", "").trim();
            if (!cosmetic.isEmpty() && !catalog.exists(cosmetic)) {
                problems.add(where + ": no cosmetic '" + cosmetic + "' in " + Catalog.RESOURCE);
            }
            long bolts = a.integer("bolts", 0);
            if (bolts < 0 || bolts > 250) {
                problems.add(where + ": bolts 0–250 (PLAN.md §6.2)");
            }
            achievements.put(id, new Achievement(id, stat, target, a.string("name", id), a.string("description", ""),
                    bolts, Math.max(0, a.integer("xp", 0)), cosmetic.isEmpty() ? null : cosmetic,
                    icon(a.string("icon", "PAPER"), where, problems)));
        }

        int bank = c.integer("daily.bank-days", 3);
        if (bank < 1 || bank > 7) {
            problems.add("daily.bank-days: 1–7");
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(RESOURCE + " is invalid:\n  " + String.join("\n  ", problems));
        }
        return new QuestConfig(dailySlots, bank, reward(c, "daily"), weeklySlots, reward(c, "weekly"), daily, weekly,
                Collections.unmodifiableMap(achievements));
    }

    private static Reward reward(ConfigView c, String kind) {
        return new Reward(Math.max(0, c.integer(kind + ".reward.bolts", 0)), Math.max(0, c.integer(kind + ".reward.xp", 0)));
    }

    private static List<String> slots(List<String> in, String where, List<String> problems) {
        List<String> out = new ArrayList<>();
        for (String s : in) {
            String m = s.trim().toLowerCase(Locale.ROOT);
            if (!m.equals("any") && !m.equals("horizons") && !m.equals("skyrealms")) {
                problems.add(where + ": '" + s + "' isn't any, horizons or skyrealms");
            }
            out.add(m);
        }
        if (out.isEmpty() || out.size() > 7) {
            problems.add(where + ": 1–7 slots");
        }
        return List.copyOf(out);
    }

    private static void checkSlots(List<String> slots, Map<String, Quest> pool, String kind, List<String> problems) {
        for (String slot : new java.util.HashSet<>(slots)) {
            long fitting = pool.values().stream().filter(q -> q.fits(slot)).count();
            long needed = slots.stream().filter(slot::equals).count();
            if (fitting < needed) {
                problems.add(kind + ": " + needed + " '" + slot + "' slots but only " + fitting + " quests fit");
            }
        }
    }

    private static Map<String, Quest> pool(ConfigView section, String kind, List<String> problems) {
        Map<String, Quest> out = new LinkedHashMap<>();
        for (String id : section.keys()) {
            ConfigView q = section.section(id);
            String where = "quests." + kind + "." + id;
            if (!id.matches("[a-z0-9-]{1,64}")) {
                problems.add(where + ": ids are a-z, 0-9 and -");
            }
            String mode = q.string("mode", "any").trim().toLowerCase(Locale.ROOT);
            if (!MODES.contains(mode)) {
                problems.add(where + ": mode is any, hub, horizons or skyrealms");
            }
            String objective = q.string("objective", "");
            if (!CORE_OBJECTIVES.contains(objective) && !PLUGIN_OBJECTIVES.contains(objective)) {
                problems.add(where + ": unknown objective '" + objective + "'");
            }
            long target = q.integer("target", 0);
            if (target <= 0) {
                problems.add(where + ": target must be positive");
            }
            Set<Material> blocks = EnumSet.noneOf(Material.class);
            for (String b : q.stringList("blocks")) {
                Material m = Material.matchMaterial(b);
                if (m == null) {
                    problems.add(where + ": '" + b + "' isn't a block");
                } else {
                    blocks.add(m);
                }
            }
            Set<EntityType> mobs = EnumSet.noneOf(EntityType.class);
            for (String m : q.stringList("mobs")) {
                try {
                    mobs.add(EntityType.valueOf(m.trim().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException e) {
                    problems.add(where + ": '" + m + "' isn't a mob");
                }
            }
            out.put(id, new Quest(id, kind, mode, objective, target, Collections.unmodifiableSet(blocks),
                    Collections.unmodifiableSet(mobs), q.string("name", id), icon(q.string("icon", "PAPER"), where,
                    problems)));
        }
        return Collections.unmodifiableMap(out);
    }

    private static String icon(String name, String where, List<String> problems) {
        Material m = Material.matchMaterial(name);
        if (m == null) {
            problems.add(where + ": '" + name + "' isn't an item");
            return Material.PAPER.name();
        }
        return m.name();
    }

    public Map<String, Quest> pool(String kind) {
        return kind.equals("weekly") ? weekly : daily;
    }

    public Reward reward(String kind) {
        return kind.equals("weekly") ? weeklyReward : dailyReward;
    }

    public List<String> slots(String kind) {
        return kind.equals("weekly") ? weeklySlots : dailySlots;
    }
}
