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
import us.rapidskies.horizons.trade.Trade;
import us.rapidskies.horizons.trade.TradeRules;

/**
 * rapid-horizons' config.yml. Parsing collects every problem instead of stopping at the
 * first, and {@link #from} throws them together.
 */
public record HorizonsSettings(Rtp rtp, Pvp pvp, Trades trades, List<Npc> npcs) {

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
                         Map<Trade, Source> sources) {

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
                decimal(c, "trades.spawner-mob-percent", 0, problems), sources);

        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", problems));
        }
        return new HorizonsSettings(rtp, pvp, trades, List.copyOf(npcs));
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
