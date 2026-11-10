package us.rapidskies.claims;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import us.rapidskies.claims.model.Flag;
import us.rapidskies.claims.town.Project;
import us.rapidskies.common.config.ConfigView;

/**
 * rapid-claims' config.yml. Parsing collects every problem instead of stopping at the
 * first, and {@link #from} throws them together.
 *
 * @param worlds       keys of the worlds where land can be claimed (the Heartland only;
 *                     PLAN.md §4.1: Frontiers, the Nether and the End can't be claimed)
 * @param defaultFlags flags a newly claimed chunk starts with
 */
public record ClaimSettings(Set<String> worlds, Allowance allowance, int defaultFlags, Towns towns,
                            Inactivity inactivity, Display display, List<Project> projects) {

    private static final Pattern PROJECT_ID = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final Pattern ITEM_KEY = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    /**
     * Personal claim allowance (PLAN.md §4.1): {@code starter} chunks, one more per
     * {@code hoursPerChunk} hours played here (up to {@code playtimeMax}), and chunks bought
     * with Crowns, the n-th (0-based) costing {@code basePrice + step × n}. Never sold for
     * money or Bolts.
     */
    public record Allowance(int starter, int hoursPerChunk, int playtimeMax, long basePrice, long step,
                            int purchaseMax) {

        public long price(int alreadyPurchased) {
            return basePrice + step * alreadyPurchased;
        }

        public int fromPlaytime(long playedMinutes) {
            if (hoursPerChunk <= 0) {
                return 0;
            }
            return (int) Math.min(playtimeMax, playedMinutes / (hoursPerChunk * 60L));
        }
    }

    /**
     * Towns (PLAN.md §4.1). The chunk cap is {@code baseChunks + perResident × members}
     * plus Project unlocks; upkeep per day is {@code upkeepBase + upkeepPerChunk × chunks}.
     */
    public record Towns(long foundCost, long renameCost, int baseChunks, int perResident, long upkeepBase,
                        long upkeepPerChunk, int graceDays, int ruinsDays, int inviteMinutes) {

        public long upkeep(int chunks) {
            return upkeepBase + upkeepPerChunk * chunks;
        }

        public int chunkCap(int members, int projectChunks) {
            return baseChunks + perResident * Math.max(0, members) + projectChunks;
        }
    }

    /** Personal claims of players away {@code days} become ruins, released {@code ruinsDays} later. */
    public record Inactivity(int days, int ruinsDays) {
    }

    /** {@code borderSeconds}: how long {@code /claim show} draws borders. */
    public record Display(int borderSeconds, boolean enterMessages) {
    }

    public Optional<Project> project(String id) {
        return projects.stream().filter(p -> p.id().equals(id)).findFirst();
    }

    public static ClaimSettings from(ConfigView c) {
        List<String> problems = new ArrayList<>();
        Set<String> worlds = Set.copyOf(c.stringList("worlds"));
        if (worlds.isEmpty()) {
            problems.add("worlds: list at least one claimable world (the Heartland is minecraft:overworld)");
        }

        Allowance allowance = new Allowance(
                nonNegative(c, "allowance.starter", 9, problems),
                nonNegative(c, "allowance.playtime.hours-per-chunk", 2, problems),
                nonNegative(c, "allowance.playtime.max", 40, problems),
                nonNegative(c, "allowance.purchase.base-price", 500, problems),
                nonNegative(c, "allowance.purchase.step", 250, problems),
                nonNegative(c, "allowance.purchase.max", 100, problems));

        int flags = 0;
        for (String name : c.stringList("flags.defaults")) {
            Optional<Flag> f = Flag.parse(name);
            if (f.isEmpty()) {
                problems.add("flags.defaults: unknown flag '" + name + "'");
            } else {
                flags = f.get().set(flags, true);
            }
        }
        if (!c.contains("flags.defaults")) {
            flags = Flag.MOB_SPAWNING.bit | Flag.LEAF_DECAY.bit;
        }

        Towns towns = new Towns(
                nonNegative(c, "towns.found-cost", 5000, problems),
                nonNegative(c, "towns.rename-cost", 1000, problems),
                positive(c, "towns.chunks.base", 16, problems),
                nonNegative(c, "towns.chunks.per-resident", 8, problems),
                nonNegative(c, "towns.upkeep.base", 50, problems),
                nonNegative(c, "towns.upkeep.per-chunk", 10, problems),
                positive(c, "towns.upkeep.grace-days", 14, problems),
                positive(c, "towns.ruins-days", 7, problems),
                positive(c, "towns.invite-minutes", 5, problems));

        Inactivity inactivity = new Inactivity(positive(c, "inactivity.days", 90, problems),
                positive(c, "inactivity.ruins-days", 7, problems));
        Display display = new Display(positive(c, "display.border-seconds", 10, problems),
                c.bool("display.enter-messages", true));

        List<Project> projects = projects(c.section("projects"), problems);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", problems));
        }
        return new ClaimSettings(worlds, allowance, flags, towns, inactivity, display, List.copyOf(projects));
    }

    private static List<Project> projects(ConfigView section, List<String> problems) {
        List<Project> out = new ArrayList<>();
        for (String id : section.keys()) {
            String where = "projects." + id;
            if (!PROJECT_ID.matcher(id).matches()) {
                problems.add(where + ": ids are lowercase letters, digits, - and _ (at most 32)");
                continue;
            }
            ConfigView p = section.section(id);
            String name = p.string("name", id);
            String icon = p.string("icon", "minecraft:bricks");
            Map<String, Long> requires = new LinkedHashMap<>();
            ConfigView req = p.section("requires");
            for (String key : req.keys()) {
                // Item keys contain a colon, which ConfigView paths don't mind, but read the raw map anyway.
                Object raw = req.asMap().get(key);
                long amount;
                try {
                    amount = raw instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(raw).trim());
                } catch (NumberFormatException e) {
                    problems.add(where + ".requires." + key + ": expected a number");
                    continue;
                }
                if (amount <= 0) {
                    problems.add(where + ".requires." + key + ": must be positive");
                    continue;
                }
                String resource = key.trim().toLowerCase(java.util.Locale.ROOT);
                if (!resource.equals(Project.CROWNS) && !resource.contains(":")) {
                    resource = "minecraft:" + resource;
                }
                if (!resource.equals(Project.CROWNS) && !ITEM_KEY.matcher(resource).matches()) {
                    problems.add(where + ".requires." + key + ": not an item key or 'crowns'");
                    continue;
                }
                requires.put(resource, amount);
            }
            if (requires.isEmpty()) {
                problems.add(where + ".requires: a project needs at least one requirement");
            }
            List<Project.Unlock> unlocks = new ArrayList<>();
            for (String u : p.stringList("unlocks")) {
                Optional<Project.Unlock> parsed = Project.Unlock.parse(u);
                if (parsed.isEmpty()) {
                    problems.add(where + ".unlocks: unknown unlock '" + u
                            + "' (warp, vault, market-stall, banner, title, chunks:<n>)");
                } else {
                    unlocks.add(parsed.get());
                }
            }
            out.add(new Project(id, name, icon, requires, unlocks));
        }
        return out;
    }

    private static int nonNegative(ConfigView c, String path, int def, List<String> problems) {
        int v = number(c, path, def, problems);
        if (v < 0) {
            problems.add(path + ": must be 0 or more");
        }
        return v;
    }

    private static int positive(ConfigView c, String path, int def, List<String> problems) {
        int v = number(c, path, def, problems);
        if (v <= 0) {
            problems.add(path + ": must be more than 0");
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
