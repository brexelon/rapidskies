package us.rapidskies.horizons;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import us.rapidskies.common.config.ConfigView;
import us.rapidskies.core.world.SpawnPoint;
import us.rapidskies.horizons.chapter.ChapterGoal;
import us.rapidskies.horizons.frontier.Modifier;
import us.rapidskies.horizons.skyborne.SkyborneScaling;
import us.rapidskies.horizons.trade.Trade;
import us.rapidskies.horizons.trade.TradePerks;
import us.rapidskies.horizons.trade.TradeRules;

/**
 * rapid-horizons' config.yml. Parsing collects every problem instead of stopping at the
 * first, and {@link #from} throws them together.
 */
public record HorizonsSettings(Rtp rtp, Pvp pvp, Trades trades, List<Npc> npcs, Frontier frontier,
                               Skyborne skyborne, List<Chapter> chapters) {

    /** The Chapter with this id, if configured. */
    public Optional<Chapter> chapter(String id) {
        return chapters.stream().filter(c -> c.id().equals(id)).findFirst();
    }

    /**
     * Rewards for taking part in an event (the dragon fight, a Skyborne fortress): Crowns,
     * Bolts (under the network's anti-farm rules, at most {@code boltsDailyCap} a day from
     * this source; 0 for no cap) and items.
     */
    public record Rewards(long crowns, long bolts, long boltsDailyCap, List<ItemReward> items) {
    }

    /** {@code "diamond 2"}: a lowercase item id and an amount. */
    public record ItemReward(String material, int amount) {

        public static ItemReward parse(String text) {
            String[] p = text.trim().toLowerCase(Locale.ROOT).replace("minecraft:", "").split("\\s+");
            if (p.length == 0 || p.length > 2 || !p[0].matches("[a-z0-9_]+")) {
                throw new IllegalArgumentException("expected \"<item> [amount]\", got \"" + text + "\"");
            }
            int amount = 1;
            if (p.length == 2) {
                try {
                    amount = Integer.parseInt(p[1]);
                } catch (NumberFormatException e) {
                    amount = 0;
                }
                if (amount < 1 || amount > 64) {
                    throw new IllegalArgumentException("amount in \"" + text + "\" must be 1 to 64");
                }
            }
            return new ItemReward(p[0], amount);
        }
    }

    /**
     * The Frontier rotation (PLAN.md §5.2): monthly resource worlds with a modifier, their own
     * Nether and End, and the Wildlands ring.
     *
     * @param lengthDays            how long each Frontier is open
     * @param warningDays           players are warned (and the next one is generated) this long before
     * @param borderSize            the Frontier's square world border (centred on 0, 0)
     * @param nether                the Nether rotates with the Frontier (portals lead there)
     * @param modifiers             modifiers in rotation order
     * @param chapters              Chapter ids, one per Frontier in order (the first Frontier gets the first)
     * @param archiveDays           closed Frontiers' worlds are kept this long in {@code archiveFolder}
     * @param travelCooldownSeconds between {@code /frontier go} uses
     */
    public record Frontier(boolean enabled, int lengthDays, int warningDays, int borderSize, boolean nether,
                           int netherBorderSize, int endBorderSize, boolean pregenerate, List<Modifier> modifiers,
                           List<String> chapters, int archiveDays, String archiveFolder, int travelCooldownSeconds,
                           Wildlands wildlands, End end, Overgrowth overgrowth, Drought drought,
                           ShatteredSkies shatteredSkies, DeepDark deepDark) {
    }

    /**
     * The Wildlands: from {@code innerRadius} out to the border (0: none). PvP is forced; ores
     * and mob loot drop double with these chances, and Trades pay more.
     */
    public record Wildlands(int innerRadius, double oreBonusPercent, double lootBonusPercent,
                            double tradeBonusPercent) {
    }

    /**
     * The End of each Frontier. It opens {@code dragonDelayHours} after the Frontier (announced
     * network-wide {@code announceMinutes} ahead), with a community dragon fight: everyone who
     * dealt at least {@code minDamage} gets the rewards once.
     */
    public record End(boolean enabled, int dragonDelayHours, int announceMinutes, double minDamage,
                      Rewards rewards) {
    }

    /**
     * Overgrowth: extra trees per chunk, mossy forest floor ({@code mossPercent} of the grass
     * becomes moss, podzol or rooted dirt in patches), {@code groundCover} ferns, bushes and
     * azaleas per chunk, and a share of hostile spawns swapped for a mob from {@code mobPool}.
     */
    public record Overgrowth(int extraTrees, double mossPercent, int groundCover, double mobVarietyPercent,
                             List<String> mobPool) {
    }

    /**
     * Drought: the seas and rivers are drained down to {@code waterLevel} (only deep water is
     * left), {@code crackedPercent} of the grass is cracked, coarse earth; water poured from
     * buckets (and melted ice) boils away, and players under the open sky by day without a
     * helmet get hungry faster ({@code sunExhaustion} a second).
     */
    public record Drought(boolean evaporateWater, double sunExhaustion, int waterLevel, double crackedPercent) {
    }

    /**
     * Shattered Skies: islands at two heights over a sea whose surface is {@code seaLevel}
     * (falls end in water, not the void). {@code islandDensity}: roughly how much of each
     * height is island, in percent. {@code oreVeins}: extra rare-ore veins (diamond, emerald,
     * gold, lapis, redstone) per chunk, inside the islands' stone.
     */
    public record ShatteredSkies(int seaLevel, double islandDensity, int oreVeins) {
    }

    /**
     * Deep Dark: caverns hollowed between {@code cavernFloor} and {@code cavernTop}
     * ({@code openness} percent of that band is open), a barren surface ({@code barrenPercent}
     * of the grass becomes coarse dirt or gravel), and natural ores broken below {@code deepY}
     * drop double with {@code deepOreBonusPercent} chance.
     */
    public record DeepDark(int cavernFloor, int cavernTop, double openness, double barrenPercent, int deepY,
                           double deepOreBonusPercent) {
    }

    /**
     * Skyborne events (PLAN.md §5.2): a fortress over the Frontier every {@code intervalMinutes}
     * (give or take {@code jitterMinutes}) while at least {@code minPlayers} are on, announced
     * network-wide {@code announceMinutes} ahead; {@code waves} waves from {@code waveMobs}, then
     * the boss, within {@code timeLimitMinutes}.
     */
    public record Skyborne(boolean enabled, int intervalMinutes, int jitterMinutes, int minPlayers,
                           int announceMinutes, int minHeight, int timeLimitMinutes, int waves,
                           List<String> waveMobs, SkyborneScaling scaling, double minDamage, Rewards rewards) {
    }

    /**
     * A Chapter (PLAN.md §5.2): a collection log for one Frontier. Finishing every entry earns
     * the cosmetic (recorded for the cosmetics engine) and {@code bolts}. Names and texts are
     * lang {@code chapter.<id>.*}.
     */
    public record Chapter(String id, String cosmetic, long bolts, List<ChapterEntry> entries) {
    }

    /** One collection-log entry: {@code goal} reached {@code amount} times. */
    public record ChapterEntry(String id, ChapterGoal goal, int amount) {
    }

    /**
     * {@code /rtp}: a random safe surface spot in {@code world} between {@code minRadius} and
     * {@code maxRadius} blocks from the centre (and inside the world border), never in a claim.
     *
     * @param portalBuild    build the arch around the portal region at startup ({@code PortalArch})
     * @param portalHologram where the floating text in front of the portal goes (the bottom of
     *                       the text; lang {@code travel.portal-hologram}), or empty for none
     */
    public record Rtp(String world, int centerX, int centerZ, int minRadius, int maxRadius, int attempts,
                      int cooldownSeconds, Optional<Region> portal, int portalRetriggerSeconds,
                      boolean portalIgnoresCooldown, boolean portalBuild, Optional<SpawnPoint> portalHologram) {
    }

    /**
     * A Citizens NPC at the spawn. Its name and click hint are lang {@code npc.<id>.name} and
     * {@code npc.<id>.hint}; without a location it isn't spawned.
     */
    public record Npc(String id, Optional<SpawnPoint> location, NpcAction action, Skin skin) {
    }

    /** What clicking an NPC does. */
    public record NpcAction(Kind kind, String value) {

        public enum Kind {
            /** Into the Heartland, like the spawn portal. */
            RTP,
            /** The {@code /trades} menu. */
            TRADES,
            /** Join a server group through the proxy queue ({@code server:hub}). */
            SERVER,
            /** Run a command as the player ({@code command:claim help}, no slash). */
            COMMAND
        }

        /** {@code rtp}, {@code trades}, {@code server:<group>} or {@code command:<command>}. */
        public static NpcAction parse(String text) {
            String t = text.trim();
            int colon = t.indexOf(':');
            String kind = (colon < 0 ? t : t.substring(0, colon)).trim().toLowerCase(Locale.ROOT);
            String value = colon < 0 ? "" : t.substring(colon + 1).trim();
            if (value.startsWith("/")) {
                value = value.substring(1);
            }
            NpcAction action = switch (kind) {
                case "rtp" -> new NpcAction(Kind.RTP, "");
                case "trades" -> new NpcAction(Kind.TRADES, "");
                case "server" -> new NpcAction(Kind.SERVER, value);
                case "command" -> new NpcAction(Kind.COMMAND, value);
                default -> throw new IllegalArgumentException(
                        "expected rtp, trades, server:<group> or command:<command>, got '" + text + "'");
            };
            if ((action.kind == Kind.SERVER || action.kind == Kind.COMMAND) && action.value.isEmpty()) {
                throw new IllegalArgumentException("'" + text + "' needs a value after the colon");
            }
            return action;
        }
    }

    /** An NPC skin: by account name, or a fixed texture and signature. All empty: the default skin. */
    public record Skin(String name, String texture, String signature) {

        public boolean fixed() {
            return !texture.isEmpty() && !signature.isEmpty();
        }
    }

    /** An axis-aligned box of blocks (inclusive), e.g. the portal arch at the spawn. */
    public record Region(String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

        public boolean contains(String worldKey, int x, int y, int z) {
            return world.equals(worldKey) && x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ
                    && z <= maxZ;
        }

        /** {@code "world, x1, y1, z1, x2, y2, z2"}, corners in any order. */
        public static Region parse(String text) {
            String[] p = text.split(",");
            if (p.length != 7) {
                throw new IllegalArgumentException("expected \"world, x1, y1, z1, x2, y2, z2\", got \"" + text + "\"");
            }
            int[] n = new int[6];
            for (int i = 0; i < 6; i++) {
                try {
                    n[i] = (int) Math.floor(Double.parseDouble(p[i + 1].trim()));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("not a number in region \"" + text + "\"", e);
                }
            }
            String world = p[0].trim();
            if (world.isEmpty()) {
                throw new IllegalArgumentException("region has no world");
            }
            return new Region(world, Math.min(n[0], n[3]), Math.min(n[1], n[4]), Math.min(n[2], n[5]),
                    Math.max(n[0], n[3]), Math.max(n[1], n[4]), Math.max(n[2], n[5]));
        }
    }

    /** Opt-in PvP (PLAN.md §5.2): {@code /pvp} cooldown and how long a hit keeps you "in combat". */
    public record Pvp(int toggleCooldownSeconds, int combatTagSeconds, CombatLog combatLog) {
    }

    /**
     * Leaving the server while combat-tagged ({@code CombatLog}).
     *
     * @param enabled     kill players who leave mid-fight
     * @param punishKicks kicks count too (staff, anti-cheat); off: only disconnects and time-outs
     * @param actionBar   show the time left in combat on the action bar
     * @param commands    console commands run after the kill ({@code <player>}, {@code <uuid>},
     *                    {@code <attacker>}), no slash
     */
    public record CombatLog(boolean enabled, boolean punishKicks, boolean actionBar, List<String> commands) {
    }

    /**
     * Trades (PLAN.md §5.2).
     *
     * @param switchCooldownHours how long after dropping a Trade before you may drop another
     * @param payoutSeconds       Crowns are paid in batches this often (and on quit)
     * @param spawnerMobPercent   Hunter rewards for mobs from spawners, in percent
     * @param sources             per Trade: what earns XP and how many Crowns an XP point is worth
     */
    public record Trades(TradeRules rules, int switchCooldownHours, int payoutSeconds, double spawnerMobPercent,
                         Map<Trade, Source> sources, TradePerks perks) {

        public Source source(Trade trade) {
            return sources.get(trade);
        }
    }

    /**
     * What a Trade rewards. {@code xp} keys are lowercase material or entity names
     * ({@code diamond_ore}, {@code zombie}), plus {@code default} as a fallback; Builder
     * uses {@code block}, Enchanter {@code level} (per enchantment level spent), Fisher
     * {@code fish}, {@code treasure} and {@code junk}.
     */
    public record Source(double crownsPerXp, Map<String, Double> xp) {

        /**
         * XP for {@code key}. Deepslate ores fall back to their stone variant, so
         * {@code deepslate_iron_ore} pays like {@code iron_ore} unless set separately.
         */
        public double xpFor(String key) {
            Double v = xp.get(key);
            if (v == null && key.startsWith("deepslate_")) {
                v = xp.get(key.substring("deepslate_".length()));
            }
            if (v == null) {
                v = xp.get("default");
            }
            return v == null ? 0 : v;
        }
    }

    static final Map<Trade, Map<String, Double>> DEFAULT_XP = defaultXp();

    private static Map<Trade, Map<String, Double>> defaultXp() {
        Map<Trade, Map<String, Double>> m = new EnumMap<>(Trade.class);
        m.put(Trade.MINER, ordered("coal_ore", 2, "copper_ore", 2, "iron_ore", 4, "gold_ore", 5, "redstone_ore", 3,
                "lapis_ore", 4, "diamond_ore", 12, "emerald_ore", 15, "nether_quartz_ore", 2, "nether_gold_ore", 2,
                "ancient_debris", 20));
        m.put(Trade.FARMER, ordered("wheat", 1, "carrots", 1, "potatoes", 1, "beetroots", 1, "nether_wart", 1,
                "cocoa", 1, "melon", 1, "pumpkin", 1.5, "sweet_berry_bush", 0.5));
        m.put(Trade.HUNTER, ordered("default", 2, "zombie", 2, "skeleton", 2, "spider", 2, "creeper", 3, "enderman", 4,
                "witch", 5, "blaze", 4, "wither_skeleton", 6, "ghast", 5, "guardian", 4, "piglin_brute", 6,
                "ravager", 10, "evoker", 10, "elder_guardian", 30, "warden", 40));
        m.put(Trade.BUILDER, ordered("block", 0.25));
        m.put(Trade.ENCHANTER, ordered("level", 2));
        m.put(Trade.FISHER, ordered("fish", 3, "treasure", 8, "junk", 1));
        return m;
    }

    private static final Map<Trade, Double> DEFAULT_CROWNS_PER_XP = new EnumMap<>(Map.of(
            Trade.MINER, 0.5, Trade.FARMER, 0.5, Trade.HUNTER, 0.5, Trade.BUILDER, 0.4, Trade.ENCHANTER, 0.4,
            Trade.FISHER, 0.5));

    private static Map<String, Double> ordered(Object... kv) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            out.put((String) kv[i], ((Number) kv[i + 1]).doubleValue());
        }
        return out;
    }

    public static HorizonsSettings from(ConfigView c) {
        List<String> problems = new ArrayList<>();

        Optional<Region> portal = Optional.empty();
        String region = c.string("rtp.portal.region", "").trim();
        if (!region.isEmpty()) {
            try {
                portal = Optional.of(Region.parse(region));
            } catch (IllegalArgumentException e) {
                problems.add("rtp.portal.region: " + e.getMessage());
            }
        }
        Optional<SpawnPoint> hologram = Optional.empty();
        String hologramAt = c.string("rtp.portal.hologram", "").trim();
        if (!hologramAt.isEmpty()) {
            try {
                hologram = Optional.of(SpawnPoint.parse(hologramAt));
            } catch (IllegalArgumentException e) {
                problems.add("rtp.portal.hologram: " + e.getMessage());
            }
        }
        int minRadius = nonNegative(c, "rtp.min-radius", 200, problems);
        int maxRadius = positive(c, "rtp.max-radius", 5000, problems);
        if (maxRadius <= minRadius) {
            problems.add("rtp.max-radius must be larger than rtp.min-radius");
        }
        Rtp rtp = new Rtp(c.string("rtp.world", "minecraft:overworld").trim(), integer(c, "rtp.center-x", 0, problems),
                integer(c, "rtp.center-z", 0, problems), minRadius, maxRadius, positive(c, "rtp.attempts", 16, problems),
                nonNegative(c, "rtp.cooldown-seconds", 300, problems), portal,
                positive(c, "rtp.portal.retrigger-seconds", 5, problems),
                c.bool("rtp.portal.ignores-cooldown", true), c.bool("rtp.portal.build", true), hologram);

        List<Npc> npcs = npcs(c, problems);

        List<String> combatCommands = c.stringList("pvp.combat-log.commands").stream()
                .map(String::trim).map(s -> s.startsWith("/") ? s.substring(1) : s).filter(s -> !s.isEmpty())
                .toList();
        CombatLog combatLog = new CombatLog(c.bool("pvp.combat-log.enabled", true),
                c.bool("pvp.combat-log.punish-kicks", false), c.bool("pvp.combat-log.action-bar", true),
                combatCommands);
        Pvp pvp = new Pvp(nonNegative(c, "pvp.toggle-cooldown-seconds", 300, problems),
                nonNegative(c, "pvp.combat-tag-seconds", 30, problems), combatLog);

        TradeRules rules = new TradeRules(
                positive(c, "trades.max-active", 2, problems),
                positive(c, "trades.max-level", 50, problems),
                positive(c, "trades.level-xp.base", 100, problems),
                nonNegative(c, "trades.level-xp.step", 25, problems),
                decimal(c, "trades.income-per-level-percent", 1, problems),
                nonNegative(c, "trades.soft-cap.daily-points", 2000, problems),
                decimal(c, "trades.soft-cap.floor-percent", 10, problems),
                nonNegative(c, "trades.claim-chunks.levels-per-chunk", 5, problems),
                nonNegative(c, "trades.claim-chunks.max", 20, problems));
        Map<Trade, Source> sources = new EnumMap<>(Trade.class);
        for (Trade t : Trade.values()) {
            String base = "trades." + t.id();
            Map<String, Double> xp = new LinkedHashMap<>();
            ConfigView section = c.section(base + ".xp");
            if (section.keys().isEmpty()) {
                xp.putAll(DEFAULT_XP.get(t));
            }
            for (String key : section.keys()) {
                Object raw = section.asMap().get(key);
                double v;
                try {
                    v = raw instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(raw).trim());
                } catch (NumberFormatException e) {
                    problems.add(base + ".xp." + key + ": expected a number");
                    continue;
                }
                if (v < 0) {
                    problems.add(base + ".xp." + key + ": must be 0 or more");
                    continue;
                }
                xp.put(key.trim().toLowerCase(Locale.ROOT).replace("minecraft:", ""), v);
            }
            double crowns = decimal(c, base + ".crowns-per-xp", DEFAULT_CROWNS_PER_XP.get(t), problems);
            sources.put(t, new Source(crowns, Map.copyOf(xp)));
        }
        Trades trades = new Trades(rules, nonNegative(c, "trades.switch-cooldown-hours", 24, problems),
                positive(c, "trades.payout-seconds", 60, problems),
                decimal(c, "trades.spawner-mob-percent", 0, problems), sources, perks(c, problems));

        List<Chapter> chapters = chapters(c, problems);
        Frontier frontier = frontier(c, problems, chapters);
        Skyborne skyborne = skyborne(c, problems);

        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", problems));
        }
        return new HorizonsSettings(rtp, pvp, trades, List.copyOf(npcs), frontier, skyborne, chapters);
    }

    private static TradePerks perks(ConfigView c, List<String> problems) {
        Map<Trade, Double> max = new EnumMap<>(TradePerks.defaults());
        for (Trade t : Trade.values()) {
            max.put(t, percent(c, "trades.perks." + t.id(), max.get(t), problems));
        }
        return new TradePerks(nonNegative(c, "trades.perks.half-level", 20, problems), max);
    }

    private static Frontier frontier(ConfigView c, List<String> problems, List<Chapter> chapters) {
        List<Modifier> modifiers = new ArrayList<>();
        List<String> listed = c.contains("frontier.modifiers") ? c.stringList("frontier.modifiers")
                : java.util.Arrays.stream(Modifier.values()).map(Modifier::configName).toList();
        for (String m : listed) {
            Optional<Modifier> parsed = Modifier.parse(m);
            if (parsed.isEmpty()) {
                problems.add("frontier.modifiers: unknown modifier '" + m + "' ("
                        + String.join(", ", java.util.Arrays.stream(Modifier.values()).map(Modifier::configName)
                        .toList()) + ")");
            } else if (!modifiers.contains(parsed.get())) {
                modifiers.add(parsed.get());
            }
        }
        if (modifiers.isEmpty()) {
            problems.add("frontier.modifiers: list at least one modifier");
        }
        List<String> chapterIds = c.stringList("frontier.chapters").stream().map(String::trim)
                .filter(s -> !s.isEmpty()).toList();
        for (String id : chapterIds) {
            if (chapters.stream().noneMatch(ch -> ch.id().equals(id))) {
                problems.add("frontier.chapters: no chapter '" + id + "' under chapters");
            }
        }
        int border = positive(c, "frontier.border-size", 6000, problems);
        int inner = nonNegative(c, "frontier.wildlands.inner-radius", 2000, problems);
        if (inner > 0 && inner >= border / 2) {
            problems.add("frontier.wildlands.inner-radius must be less than half of frontier.border-size");
        }
        if (inner > 0 && inner < 300) {
            problems.add("frontier.wildlands.inner-radius: at least 300, so the safe centre has room to land");
        }
        Wildlands wildlands = new Wildlands(inner, percent(c, "frontier.wildlands.ore-bonus-percent", 50, problems),
                percent(c, "frontier.wildlands.loot-bonus-percent", 50, problems),
                decimal(c, "frontier.wildlands.trade-bonus-percent", 25, problems));
        End end = new End(c.bool("frontier.end.enabled", true),
                nonNegative(c, "frontier.end.dragon-delay-hours", 24, problems),
                nonNegative(c, "frontier.end.announce-minutes", 10, problems),
                decimal(c, "frontier.end.min-damage", 10, problems),
                rewards(c, "frontier.end.rewards", 500, 20, 0, problems));
        List<String> pool = c.contains("frontier.overgrowth.mob-pool") ? ids(c, "frontier.overgrowth.mob-pool", problems)
                : List.of("husk", "stray", "witch", "cave_spider", "zombie_villager");
        Overgrowth overgrowth = new Overgrowth(nonNegative(c, "frontier.overgrowth.extra-trees", 3, problems),
                percent(c, "frontier.overgrowth.moss-percent", 20, problems),
                nonNegative(c, "frontier.overgrowth.ground-cover", 6, problems),
                percent(c, "frontier.overgrowth.mob-variety-percent", 30, problems), pool);
        Drought drought = new Drought(c.bool("frontier.drought.evaporate-water", true),
                decimal(c, "frontier.drought.sun-exhaustion", 0.05, problems),
                between(c, "frontier.drought.water-level", 48, -48, 62, problems),
                percent(c, "frontier.drought.cracked-percent", 35, problems));
        ShatteredSkies skies = new ShatteredSkies(
                between(c, "frontier.shattered-skies.sea-level", 24, -40, 60, problems),
                between(c, "frontier.shattered-skies.island-density", 25, 5, 60, problems),
                between(c, "frontier.shattered-skies.ore-veins", 4, 0, 32, problems));
        int cavernFloor = between(c, "frontier.deep-dark.cavern-floor", -48, -54, 40, problems);
        int cavernTop = between(c, "frontier.deep-dark.cavern-top", 8, -38, 40, problems);
        if (cavernTop - cavernFloor < 16) {
            problems.add("frontier.deep-dark.cavern-top: at least 16 blocks above cavern-floor");
        }
        DeepDark deepDark = new DeepDark(cavernFloor, cavernTop,
                between(c, "frontier.deep-dark.openness", 35, 5, 70, problems),
                percent(c, "frontier.deep-dark.barren-percent", 50, problems),
                integer(c, "frontier.deep-dark.deep-y", 0, problems),
                percent(c, "frontier.deep-dark.deep-ore-bonus-percent", 25, problems));
        String archive = c.string("frontier.archive-folder", "frontier-archive").trim();
        if (archive.isEmpty() || archive.contains("..")) {
            problems.add("frontier.archive-folder: a folder name inside the server folder");
        }
        return new Frontier(c.bool("frontier.enabled", true), positive(c, "frontier.length-days", 30, problems),
                nonNegative(c, "frontier.warning-days", 7, problems), border, c.bool("frontier.nether.enabled", true),
                positive(c, "frontier.nether.border-size", 1500, problems),
                positive(c, "frontier.end.border-size", 3000, problems), c.bool("frontier.pregenerate", true),
                List.copyOf(modifiers), chapterIds, nonNegative(c, "frontier.archive-days", 30, problems), archive,
                nonNegative(c, "frontier.travel-cooldown-seconds", 60, problems), wildlands, end, overgrowth, drought,
                skies, deepDark);
    }

    private static Skyborne skyborne(ConfigView c, List<String> problems) {
        List<String> mobs = c.contains("skyborne.wave-mobs") ? ids(c, "skyborne.wave-mobs", problems)
                : List.of("zombie", "skeleton", "spider", "vindicator", "pillager");
        if (mobs.isEmpty()) {
            problems.add("skyborne.wave-mobs: list at least one mob");
        }
        SkyborneScaling scaling = new SkyborneScaling(positive(c, "skyborne.scaling.wave-base", 6, problems),
                nonNegative(c, "skyborne.scaling.wave-per-player", 2, problems),
                nonNegative(c, "skyborne.scaling.wave-growth", 2, problems),
                positive(c, "skyborne.scaling.wave-max", 40, problems),
                decimal(c, "skyborne.scaling.boss-health", 300, problems),
                decimal(c, "skyborne.scaling.boss-health-per-player-percent", 35, problems));
        int minHeight = integer(c, "skyborne.min-height", 170, problems);
        if (minHeight < 100 || minHeight > 280) {
            problems.add("skyborne.min-height: between 100 and 280");
        }
        return new Skyborne(c.bool("skyborne.enabled", true), positive(c, "skyborne.interval-minutes", 240, problems),
                nonNegative(c, "skyborne.jitter-minutes", 30, problems),
                nonNegative(c, "skyborne.min-players", 3, problems),
                positive(c, "skyborne.announce-minutes", 10, problems), minHeight,
                positive(c, "skyborne.time-limit-minutes", 20, problems), positive(c, "skyborne.waves", 3, problems),
                mobs, scaling, decimal(c, "skyborne.min-damage", 20, problems),
                rewards(c, "skyborne.rewards", 400, 15, 20, problems));
    }

    private static List<Chapter> chapters(ConfigView c, List<String> problems) {
        List<Chapter> out = new ArrayList<>();
        ConfigView all = c.section("chapters");
        for (String id : all.keys()) {
            String where = "chapters." + id;
            if (!id.matches("[a-z0-9-]+")) {
                problems.add(where + ": ids are lowercase letters, digits and - (they name lang keys)");
                continue;
            }
            ConfigView ch = all.section(id);
            List<ChapterEntry> entries = new ArrayList<>();
            ConfigView entrySection = ch.section("entries");
            for (String entry : entrySection.keys()) {
                String at = where + ".entries." + entry;
                if (!entry.matches("[a-z0-9-]+")) {
                    problems.add(at + ": ids are lowercase letters, digits and -");
                    continue;
                }
                ConfigView e = entrySection.section(entry);
                ChapterGoal goal;
                try {
                    goal = ChapterGoal.parse(e.string("goal", ""));
                } catch (IllegalArgumentException ex) {
                    problems.add(at + ".goal: " + ex.getMessage());
                    continue;
                }
                int amount = integer(e, "amount", 1, problems);
                if (amount < 1) {
                    problems.add(at + ".amount: at least 1");
                }
                if ((goal.kind() == ChapterGoal.Kind.VISIT || goal.kind() == ChapterGoal.Kind.DISTANCE) && amount != 1) {
                    problems.add(at + ".amount: " + goal.kind().id() + " goals are reached once");
                }
                entries.add(new ChapterEntry(entry, goal, Math.max(1, amount)));
            }
            if (entries.isEmpty()) {
                problems.add(where + ".entries: a chapter needs at least one entry");
            }
            if (entries.size() > 28) {
                problems.add(where + ".entries: at most 28 (the /chapter menu's room)");
            }
            int bolts = integer(ch, "reward.bolts", 100, problems);
            out.add(new Chapter(id, ch.string("reward.cosmetic", "").trim(), Math.max(0, bolts),
                    List.copyOf(entries)));
        }
        return List.copyOf(out);
    }

    private static Rewards rewards(ConfigView c, String base, int crowns, int bolts, int cap, List<String> problems) {
        List<ItemReward> items = new ArrayList<>();
        for (String item : c.stringList(base + ".items")) {
            try {
                items.add(ItemReward.parse(item));
            } catch (IllegalArgumentException e) {
                problems.add(base + ".items: " + e.getMessage());
            }
        }
        return new Rewards(nonNegative(c, base + ".crowns", crowns, problems),
                nonNegative(c, base + ".bolts", bolts, problems),
                nonNegative(c, base + ".bolts-daily-cap", cap, problems), List.copyOf(items));
    }

    /** A list of lowercase mob ids. */
    private static List<String> ids(ConfigView c, String path, List<String> problems) {
        List<String> out = new ArrayList<>();
        for (String raw : c.stringList(path)) {
            String id = raw.trim().toLowerCase(Locale.ROOT).replace("minecraft:", "");
            if (!id.matches("[a-z0-9_]+")) {
                problems.add(path + ": '" + raw + "' isn't a mob id");
            } else {
                out.add(id);
            }
        }
        return List.copyOf(out);
    }

    private static double percent(ConfigView c, String path, double def, List<String> problems) {
        double v = decimal(c, path, def, problems);
        if (v > 100) {
            problems.add(path + ": at most 100 (percent)");
        }
        return v;
    }

    private static List<Npc> npcs(ConfigView c, List<String> problems) {
        List<Npc> out = new ArrayList<>();
        ConfigView all = c.section("npcs");
        for (String id : all.keys()) {
            String where = "npcs." + id;
            if (!id.matches("[a-z0-9_-]+")) {
                problems.add(where + ": ids are lowercase letters, digits, - and _ (they name lang keys)");
                continue;
            }
            ConfigView n = all.section(id);
            Optional<SpawnPoint> location = Optional.empty();
            String at = n.string("location", "").trim();
            if (!at.isEmpty()) {
                try {
                    location = Optional.of(SpawnPoint.parse(at));
                } catch (IllegalArgumentException e) {
                    problems.add(where + ".location: " + e.getMessage());
                }
            }
            NpcAction action;
            try {
                action = NpcAction.parse(n.string("action", ""));
            } catch (IllegalArgumentException e) {
                problems.add(where + ".action: " + e.getMessage());
                continue;
            }
            Skin skin = new Skin(n.string("skin", "").trim(), n.string("skin-texture", "").trim(),
                    n.string("skin-signature", "").trim());
            if (skin.texture().isEmpty() != skin.signature().isEmpty()) {
                problems.add(where + ": skin-texture and skin-signature go together");
            }
            out.add(new Npc(id, location, action, skin));
        }
        return out;
    }

    private static int integer(ConfigView c, String path, int def, List<String> problems) {
        try {
            return c.integer(path, def);
        } catch (IllegalArgumentException e) {
            problems.add(e.getMessage());
            return def;
        }
    }

    private static int between(ConfigView c, String path, int def, int min, int max, List<String> problems) {
        int v = integer(c, path, def, problems);
        if (v < min || v > max) {
            problems.add(path + ": must be " + min + " to " + max);
        }
        return v;
    }

    private static int nonNegative(ConfigView c, String path, int def, List<String> problems) {
        int v = integer(c, path, def, problems);
        if (v < 0) {
            problems.add(path + ": must be 0 or more");
        }
        return v;
    }

    private static int positive(ConfigView c, String path, int def, List<String> problems) {
        int v = integer(c, path, def, problems);
        if (v <= 0) {
            problems.add(path + ": must be more than 0");
        }
        return v;
    }

    private static double decimal(ConfigView c, String path, double def, List<String> problems) {
        Object v = c.get(path);
        if (v == null) {
            return def;
        }
        try {
            double d = v instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(v).trim());
            if (d < 0) {
                problems.add(path + ": must be 0 or more");
            }
            return d;
        } catch (NumberFormatException e) {
            problems.add(path + ": expected a number, got '" + v + "'");
            return def;
        }
    }
}
