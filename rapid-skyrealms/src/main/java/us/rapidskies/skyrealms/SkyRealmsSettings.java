package us.rapidskies.skyrealms;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.random.RandomGenerator;
import org.bukkit.Material;
import us.rapidskies.common.config.ConfigView;
import us.rapidskies.skyrealms.expedition.Theme;
import us.rapidskies.skyrealms.island.IslandLayouts;
import us.rapidskies.skyrealms.tier.TierGoal;

/**
 * rapid-skyrealms' config.yml, checked as a whole: every problem is collected and reported
 * at once, and a bad config never half-loads ({@link #from} throws instead).
 */
public record SkyRealmsSettings(Islands islands, List<Tier> tiers, int manageRoleWeight, Shop shop,
                                Expeditions expeditions, Routes routes, Seasons seasons, Market market) {

    /** {@code <material> [x<n>|x<min>-<max>]}: an item and how many (a range is rolled). */
    public record ItemAmount(Material material, int min, int max) {

        public static ItemAmount parse(String text) {
            String[] p = text.trim().split("\\s+");
            if (p.length == 0 || p.length > 2 || p[0].isEmpty()) {
                throw new IllegalArgumentException("expected '<item> [x<amount>]', got '" + text + "'");
            }
            Material material = SkyRealmsSettings.material(p[0]);
            int min = 1;
            int max = 1;
            if (p.length == 2) {
                String a = p[1].toLowerCase(Locale.ROOT);
                if (!a.startsWith("x")) {
                    throw new IllegalArgumentException("amount in '" + text + "' must look like x8 or x1-3");
                }
                String[] range = a.substring(1).split("-", 2);
                try {
                    min = Integer.parseInt(range[0]);
                    max = range.length == 2 ? Integer.parseInt(range[1]) : min;
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("amount in '" + text + "' isn't a number", e);
                }
                if (min < 0 || max < min || max > 64 * 36) {
                    throw new IllegalArgumentException("amount in '" + text + "' is out of range");
                }
            }
            return new ItemAmount(material, min, max);
        }

        /** A roll in [min, max]. */
        public int roll(RandomGenerator random) {
            return min == max ? min : min + random.nextInt(max - min + 1);
        }
    }

    /** A material key ({@code wheat}, {@code minecraft:wheat}); anything else is an error. */
    public static Material material(String key) {
        String k = key.trim().toLowerCase(Locale.ROOT);
        if (k.startsWith("minecraft:")) {
            k = k.substring("minecraft:".length());
        }
        Material m = Material.matchMaterial(k);
        if (m == null || m.isLegacy() || m == Material.AIR) {
            throw new IllegalArgumentException("'" + key + "' isn't an item or block");
        }
        return m;
    }

    public record Islands(List<String> layouts, List<ItemAmount> starterChest) {
    }

    /**
     * A Realm Tier. {@code requires} are the goals of the tier below it that unlock this one;
     * the first tier has none.
     */
    public record Tier(String id, int index, Material icon, String biome, long cost, List<TierGoal> requires,
                       Map<Material, Integer> generator, List<ItemAmount> rewards) {
    }

    public record ShopItem(Material material, double buy, double sell) {
        public boolean buyable() {
            return buy > 0;
        }

        public boolean sellable() {
            return sell > 0;
        }
    }

    public record Category(String id, Material icon, String tier, List<ShopItem> items) {
    }

    public record Shop(long dailyFullRate, int reducedPercent, int floorPercent, List<Category> categories) {

        /** The category and price of {@code material} among those {@code unlocked}, if it sells. */
        public Optional<ShopItem> sellable(Material material, java.util.function.Predicate<Category> unlocked) {
            for (Category c : categories) {
                if (!unlocked.test(c)) {
                    continue;
                }
                for (ShopItem i : c.items()) {
                    if (i.material() == material && i.sellable()) {
                        return Optional.of(i);
                    }
                }
            }
            return Optional.empty();
        }
    }

    /** A resource block on an Expedition island: what breaking it earns. */
    public record Node(int points, List<ItemAmount> loot) {
    }

    public record ThemeConfig(Map<Material, Node> nodes, List<ItemAmount> heart) {
    }

    public record Expeditions(String world, int height, int spacing, int intervalMinutes, int jitterMinutes,
                              int warningMinutes, int durationMinutes, int minPlayers, List<Theme> themes,
                              int hitsPerPlayer, int minHits, int coinsPerPoint, int minPoints, long bolts,
                              long boltsDailyCap, Map<Theme, ThemeConfig> themeConfig) {
    }

    public record Routes(int maxPerIsland, int perTier, int taxPercent, Map<String, Integer> intervals,
                         int minQuantity, int maxQuantity, long minPrice, long maxPrice, int offerHours,
                         int maxMissed, int checkSeconds) {
    }

    public record Award(int top, String cosmetic, long bolts) {
        /** The cosmetic id for season {@code n}. */
        public String cosmeticFor(int season) {
            return cosmetic.replace("<n>", Integer.toString(season));
        }
    }

    public record Seasons(boolean enabled, int lengthDays, List<Award> awards) {
        /** The award for an island ranked {@code rank} (1-based), if any: the first whose top covers it. */
        public Optional<Award> award(int rank) {
            return awards.stream().filter(a -> rank <= a.top()).findFirst();
        }
    }

    public record Market(int levelsPerListing, int maxExtra) {
    }

    /** The tier with {@code id}, if configured. */
    public Optional<Tier> tier(String id) {
        return tiers.stream().filter(t -> t.id().equals(id)).findFirst();
    }

    public Tier firstTier() {
        return tiers.getFirst();
    }

    /** The tier after {@code current}, if there is one. */
    public Optional<Tier> next(Tier current) {
        int i = current.index() + 1;
        return i < tiers.size() ? Optional.of(tiers.get(i)) : Optional.empty();
    }

    public static SkyRealmsSettings from(ConfigView c) {
        List<String> problems = new ArrayList<>();

        // ---- islands
        List<String> layouts = c.stringList("islands.layouts").stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
        if (layouts.isEmpty()) {
            problems.add("islands.layouts: name at least one layout (" + IslandLayouts.IDS + ")");
        }
        for (String l : layouts) {
            if (!IslandLayouts.IDS.contains(l)) {
                problems.add("islands.layouts: unknown layout '" + l + "' (have " + IslandLayouts.IDS + ")");
            }
        }
        Islands islands = new Islands(layouts, items(c.stringList("islands.starter-chest"), "islands.starter-chest",
                problems));

        // ---- tiers
        List<Tier> tiers = new ArrayList<>();
        ConfigView tiersView = c.section("tiers");
        for (String id : tiersView.keys()) {
            ConfigView t = tiersView.section(id);
            String base = "tiers." + id;
            List<TierGoal> goals = new ArrayList<>();
            for (String g : t.stringList("requires")) {
                try {
                    goals.add(TierGoal.parse(g));
                } catch (IllegalArgumentException e) {
                    problems.add(base + ".requires: " + e.getMessage());
                }
            }
            if (tiers.isEmpty() && !goals.isEmpty()) {
                problems.add(base + ": the first tier can't have requirements (every island starts on it)");
            }
            if (!tiers.isEmpty() && goals.isEmpty()) {
                problems.add(base + ".requires: a tier after the first needs at least one goal");
            }
            if (goals.stream().map(TierGoal::id).distinct().count() != goals.size()) {
                problems.add(base + ".requires: a goal is listed twice");
            }
            Map<Material, Integer> generator = new LinkedHashMap<>();
            ConfigView gen = t.section("generator");
            for (String block : gen.keys()) {
                try {
                    Material m = material(block);
                    int weight = gen.integer(block, 0);
                    if (weight <= 0) {
                        throw new IllegalArgumentException("weight of '" + block + "' must be above 0");
                    }
                    generator.put(m, weight);
                } catch (IllegalArgumentException e) {
                    problems.add(base + ".generator: " + e.getMessage());
                }
            }
            if (generator.isEmpty()) {
                problems.add(base + ".generator: list at least one block");
            }
            long cost = t.integer("cost", 0);
            if (cost < 0) {
                problems.add(base + ".cost can't be negative");
            }
            Material icon = Material.GRASS_BLOCK;
            try {
                icon = material(t.string("icon", "grass_block"));
            } catch (IllegalArgumentException ex) {
                problems.add(base + ".icon: " + ex.getMessage());
            }
            tiers.add(new Tier(id, tiers.size(), icon, t.string("biome", "plains").toLowerCase(Locale.ROOT), cost,
                    List.copyOf(goals), Collections.unmodifiableMap(generator),
                    items(t.stringList("rewards"), base + ".rewards", problems)));
        }
        if (tiers.isEmpty()) {
            problems.add("tiers: configure at least one Realm Tier");
        }

        int roleWeight = c.integer("manage-role-weight", 2);

        // ---- shop
        ConfigView s = c.section("shop");
        List<Category> categories = new ArrayList<>();
        ConfigView cats = s.section("categories");
        for (String id : cats.keys()) {
            ConfigView cat = cats.section(id);
            String base = "shop.categories." + id;
            String tier = cat.string("tier", tiers.isEmpty() ? "" : tiers.getFirst().id());
            if (tiers.stream().noneMatch(t -> t.id().equals(tier))) {
                problems.add(base + ".tier: no tier '" + tier + "'");
            }
            Material icon = Material.CHEST;
            try {
                icon = material(cat.string("icon", "chest"));
            } catch (IllegalArgumentException e) {
                problems.add(base + ".icon: " + e.getMessage());
            }
            List<ShopItem> items = new ArrayList<>();
            for (String line : cat.stringList("items")) {
                try {
                    items.add(shopItem(line));
                } catch (IllegalArgumentException e) {
                    problems.add(base + ".items: " + e.getMessage());
                }
            }
            if (items.isEmpty()) {
                problems.add(base + ".items: list at least one item");
            }
            if (items.size() > 45) {
                problems.add(base + ".items: at most 45 per category (one menu page)");
            }
            categories.add(new Category(id, icon, tier, List.copyOf(items)));
        }
        if (categories.size() > 28) {
            problems.add("shop.categories: at most 28");
        }
        Shop shop = new Shop(Math.max(0, s.integer("daily-full-rate", 20_000)),
                percent(s, "reduced-percent", 50, "shop", problems), percent(s, "floor-percent", 25, "shop", problems),
                List.copyOf(categories));

        // ---- expeditions
        ConfigView e = c.section("expeditions");
        List<Theme> themes = new ArrayList<>();
        for (String t : e.stringList("themes")) {
            Theme.byId(t).ifPresentOrElse(themes::add,
                    () -> problems.add("expeditions.themes: unknown theme '" + t + "' (have " + Theme.ids() + ")"));
        }
        if (themes.isEmpty()) {
            problems.add("expeditions.themes: name at least one theme");
        }
        Map<Theme, ThemeConfig> themeConfig = new EnumMap<>(Theme.class);
        for (Theme theme : Theme.values()) {
            ConfigView tc = e.section("themes-config." + theme.id());
            String base = "expeditions.themes-config." + theme.id();
            Map<Material, Node> nodes = new EnumMap<>(Material.class);
            ConfigView nv = tc.section("nodes");
            for (String block : nv.keys()) {
                try {
                    Material m = material(block);
                    ConfigView n = nv.section(block);
                    int points = n.integer("points", 1);
                    if (points < 1) {
                        throw new IllegalArgumentException("points of '" + block + "' must be at least 1");
                    }
                    nodes.put(m, new Node(points, items(n.stringList("loot"), base + ".nodes." + block, problems)));
                } catch (IllegalArgumentException ex) {
                    problems.add(base + ".nodes: " + ex.getMessage());
                }
            }
            if (themes.contains(theme)) {
                for (Material needed : theme.nodeMaterials()) {
                    if (!nodes.containsKey(needed)) {
                        problems.add(base + ".nodes: the " + theme.id() + " island has " + needed.getKey().getKey()
                                + " resource blocks; say what they give");
                    }
                }
            }
            themeConfig.put(theme, new ThemeConfig(Collections.unmodifiableMap(nodes),
                    items(tc.stringList("heart"), base + ".heart", problems)));
        }
        Expeditions expeditions = new Expeditions(e.string("world", "rapidskies:expeditions"),
                e.integer("height", 120), Math.max(256, e.integer("spacing", 512)),
                positive(e, "interval-minutes", 180, "expeditions", problems),
                Math.max(0, e.integer("jitter-minutes", 20)), Math.max(0, e.integer("warning-minutes", 5)),
                positive(e, "duration-minutes", 15, "expeditions", problems), Math.max(0, e.integer("min-players", 2)),
                List.copyOf(themes), positive(e, "heart.hits-per-player", 12, "expeditions", problems),
                positive(e, "heart.min-hits", 24, "expeditions", problems),
                Math.max(0, e.integer("coins-per-point", 6)), Math.max(0, e.integer("min-points", 5)),
                Math.max(0, e.integer("bolts", 10)), Math.max(0, e.integer("bolts-daily-cap", 30)),
                Collections.unmodifiableMap(themeConfig));
        if (expeditions.jitterMinutes() >= expeditions.intervalMinutes()) {
            problems.add("expeditions.jitter-minutes must be below interval-minutes");
        }
        if (expeditions.height() < 0 || expeditions.height() > 280) {
            problems.add("expeditions.height must be between 0 and 280");
        }

        // ---- routes
        ConfigView r = c.section("routes");
        Map<String, Integer> intervals = new LinkedHashMap<>();
        ConfigView iv = r.section("intervals");
        for (String name : iv.keys()) {
            int hours = iv.integer(name, 0);
            if (hours < 1) {
                problems.add("routes.intervals." + name + " must be at least 1 hour");
            } else {
                intervals.put(name.toLowerCase(Locale.ROOT), hours);
            }
        }
        if (intervals.isEmpty()) {
            intervals.put("daily", 24);
            intervals.put("weekly", 168);
        }
        Routes routes = new Routes(positive(r, "max-per-island", 2, "routes", problems),
                Math.max(0, r.integer("per-tier", 1)), percent(r, "tax-percent", 3, "routes", problems),
                Collections.unmodifiableMap(intervals), positive(r, "min-quantity", 16, "routes", problems),
                positive(r, "max-quantity", 1728, "routes", problems), positive(r, "min-price", 10, "routes", problems),
                positive(r, "max-price", 1_000_000, "routes", problems), positive(r, "offer-hours", 72, "routes", problems),
                positive(r, "max-missed", 3, "routes", problems), Math.max(10, r.integer("check-seconds", 60)));
        if (routes.minQuantity() > routes.maxQuantity() || routes.minPrice() > routes.maxPrice()) {
            problems.add("routes: a min is above its max");
        }

        // ---- seasons
        ConfigView se = c.section("seasons");
        List<Award> awards = new ArrayList<>();
        if (se.get("awards") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    @SuppressWarnings("unchecked")
                    ConfigView a = ConfigView.of((Map<String, Object>) m);
                    int top = a.integer("top", 0);
                    String cosmetic = a.string("cosmetic", "");
                    if (top < 1 || cosmetic.isBlank()) {
                        problems.add("seasons.awards: each needs top (1+) and cosmetic");
                        continue;
                    }
                    awards.add(new Award(top, cosmetic, Math.max(0, a.integer("bolts", 0))));
                } else {
                    problems.add("seasons.awards: each entry is {top, cosmetic, bolts}");
                }
            }
        }
        awards.sort(java.util.Comparator.comparingInt(Award::top));
        Seasons seasons = new Seasons(se.bool("enabled", true), positive(se, "length-days", 90, "seasons", problems),
                List.copyOf(awards));

        Market market = new Market(positive(c, "market.levels-per-listing", 100, "", problems),
                Math.max(0, c.integer("market.max-extra", 10)));

        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("config.yml has " + problems.size() + " problem(s):\n  - "
                    + String.join("\n  - ", problems));
        }
        return new SkyRealmsSettings(islands, List.copyOf(tiers), roleWeight, shop, expeditions, routes, seasons,
                market);
    }

    /** {@code "<material> <buy> <sell>"}; selling may never pay more than buying costs. */
    static ShopItem shopItem(String line) {
        String[] p = line.trim().split("\\s+");
        if (p.length != 3) {
            throw new IllegalArgumentException("expected '<item> <buy> <sell>', got '" + line + "'");
        }
        Material m = material(p[0]);
        double buy;
        double sell;
        try {
            buy = Double.parseDouble(p[1]);
            sell = Double.parseDouble(p[2]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("prices in '" + line + "' aren't numbers", e);
        }
        if (buy < 0 || sell < 0 || !Double.isFinite(buy) || !Double.isFinite(sell)) {
            throw new IllegalArgumentException("prices in '" + line + "' can't be negative");
        }
        if (buy == 0 && sell == 0) {
            throw new IllegalArgumentException("'" + line + "' neither buys nor sells");
        }
        if (buy > 0 && sell > buy) {
            throw new IllegalArgumentException("'" + line + "' sells for more than it costs (free Coins)");
        }
        return new ShopItem(m, buy, sell);
    }

    private static List<ItemAmount> items(List<String> lines, String path, List<String> problems) {
        List<ItemAmount> out = new ArrayList<>();
        for (String line : lines) {
            try {
                out.add(ItemAmount.parse(line));
            } catch (IllegalArgumentException e) {
                problems.add(path + ": " + e.getMessage());
            }
        }
        return List.copyOf(out);
    }

    private static int positive(ConfigView c, String key, int def, String section, List<String> problems) {
        int v = c.integer(key, def);
        if (v < 1) {
            problems.add((section.isEmpty() ? "" : section + ".") + key + " must be at least 1");
            return def;
        }
        return v;
    }

    private static int percent(ConfigView c, String key, int def, String section, List<String> problems) {
        int v = c.integer(key, def);
        if (v < 0 || v > 100) {
            problems.add(section + "." + key + " must be 0 to 100");
            return def;
        }
        return v;
    }
}
