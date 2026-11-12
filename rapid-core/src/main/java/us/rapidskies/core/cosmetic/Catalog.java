package us.rapidskies.core.cosmetic;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.Particle;
import us.rapidskies.common.config.ConfigView;
import us.rapidskies.common.rank.Rank;
import us.rapidskies.common.shop.FeaturedRotation;
import us.rapidskies.common.shop.ShopRepository;
import us.rapidskies.core.cosmetic.Cosmetic.Effect;
import us.rapidskies.core.cosmetic.Cosmetic.Obtain;
import us.rapidskies.core.cosmetic.Cosmetic.Rarity;

/**
 * The cosmetics catalog and the Bolt shop's stock ({@code cosmetics.yml}, PLAN.md §6.1,
 * §6.2), read from the rapid-core jar so every server agrees. A mistake anywhere refuses the
 * whole file, listing every problem, rather than selling something half-configured.
 */
public final class Catalog {

    public static final String RESOURCE = "cosmetics.yml";

    /** Particles that need no extra data (every one listed is in 1.20); dust takes a color. */
    private static final String DUST = "dust";

    /** A 30-day rank pass sold for Bolts (§6.1). */
    public record RankPass(Rank rank, long price, int days, String icon, ShopRepository.Tag tag) {
    }

    private final Map<String, Cosmetic> cosmetics;
    private final List<Template> templates;
    private final int featuredSize;
    private final Map<Rank, RankPass> rankPasses;

    /** A cosmetic whose name holds {@code <n>} (season awards): any number fills it. */
    private record Template(Pattern id, Cosmetic cosmetic) {
    }

    private Catalog(Map<String, Cosmetic> cosmetics, List<Template> templates, int featuredSize,
                    Map<Rank, RankPass> rankPasses) {
        this.cosmetics = cosmetics;
        this.templates = templates;
        this.featuredSize = featuredSize;
        this.rankPasses = rankPasses;
    }

    public static Catalog load(ClassLoader loader) throws IOException {
        try (InputStream in = loader.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IOException("missing bundled resource " + RESOURCE);
            }
            return parse(ConfigView.load(in));
        }
    }

    /** Parses the catalog; throws with every problem found. */
    public static Catalog parse(ConfigView c) {
        List<String> problems = new ArrayList<>();
        Map<String, Cosmetic> cosmetics = new LinkedHashMap<>();
        List<Template> templates = new ArrayList<>();
        ConfigView all = c.section("cosmetics");
        for (String typeKey : all.keys()) {
            Optional<CosmeticType> type = CosmeticType.byId(typeKey);
            if (type.isEmpty()) {
                problems.add("cosmetics." + typeKey + ": not a cosmetic type");
                continue;
            }
            ConfigView section = all.section(typeKey);
            for (String name : section.keys()) {
                String id = type.get().id() + ":" + name;
                try {
                    Cosmetic cosmetic = cosmetic(id, type.get(), section.section(name));
                    if (name.contains("<n>")) {
                        templates.add(new Template(Pattern.compile(Pattern.quote(id).replace("<n>", "\\E(\\d+)\\Q")),
                                cosmetic));
                    } else {
                        cosmetics.put(id, cosmetic);
                    }
                } catch (IllegalArgumentException e) {
                    problems.add(id + ": " + e.getMessage());
                }
            }
        }
        int featuredSize = c.integer("featured.size", 4);
        if (featuredSize < 1 || featuredSize > 7) {
            problems.add("featured.size: 1 to 7 (one menu row)");
        }
        Map<Rank, RankPass> passes = new EnumMap<>(Rank.class);
        ConfigView passSection = c.section("rank-passes");
        for (String group : passSection.keys()) {
            ConfigView p = passSection.section(group);
            try {
                Rank rank = Rank.byGroup(group);
                if (rank == Rank.DEFAULT) {
                    throw new IllegalArgumentException("not a paid rank");
                }
                long price = p.integer("price", 0);
                int days = p.integer("days", 30);
                if (price <= 0 || days <= 0) {
                    throw new IllegalArgumentException("price and days must be positive");
                }
                passes.put(rank, new RankPass(rank, price, days, material(p.string("icon", "GOLD_INGOT")),
                        ShopRepository.Tag.parse(p.string("tag", "CONVENIENCE"))));
            } catch (IllegalArgumentException e) {
                problems.add("rank-passes." + group + ": " + e.getMessage());
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(RESOURCE + " is invalid:\n  " + String.join("\n  ", problems));
        }
        return new Catalog(Collections.unmodifiableMap(cosmetics), List.copyOf(templates), featuredSize,
                Collections.unmodifiableMap(passes));
    }

    private static Cosmetic cosmetic(String id, CosmeticType type, ConfigView e) {
        String name = e.string("name", "").trim();
        String display = e.string("display", "").trim();
        if (name.isEmpty() || display.isEmpty()) {
            throw new IllegalArgumentException("needs a name and a display");
        }
        Rarity rarity = enumValue(Rarity.class, e.string("rarity", "common"), "rarity");
        Obtain obtain = enumValue(Obtain.class, e.string("obtain", ""), "obtain");
        long price = e.integer("price", 0);
        if (obtain.bought() && price <= 0) {
            throw new IllegalArgumentException("obtain " + obtain.id() + " needs a price in Bolts");
        }
        if (!obtain.bought() && price != 0) {
            throw new IllegalArgumentException("only shop and featured cosmetics have a price");
        }
        Rank rank = null;
        if (obtain == Obtain.RANK) {
            rank = Rank.byGroup(e.string("rank", ""));
            if (rank == Rank.DEFAULT) {
                throw new IllegalArgumentException("obtain rank needs a paid rank");
            }
        }
        if (obtain.bought() && id.contains("<n>")) {
            throw new IllegalArgumentException("numbered cosmetics can't be sold");
        }
        NamedTextColor color = null;
        Effect effect = null;
        switch (type) {
            case NAME_COLOR -> {
                color = NamedTextColor.NAMES.value(display.toLowerCase(Locale.ROOT));
                if (color == null) {
                    throw new IllegalArgumentException("'" + display + "' isn't a named chat color");
                }
            }
            case TRAIL, ARROW -> effect = effect(display);
            default -> {
            }
        }
        return new Cosmetic(id, type, name, display, rarity, obtain, price, rank,
                material(e.string("icon", "PAPER")), e.string("hint", ""), color, effect);
    }

    static Effect effect(String display) {
        String[] parts = display.trim().split("\\s+");
        if (parts[0].equalsIgnoreCase(DUST)) {
            if (parts.length != 2 || !parts[1].matches("#[0-9A-Fa-f]{6}")) {
                throw new IllegalArgumentException("dust needs a color: \"dust #RRGGBB\"");
            }
            return new Effect(Particle.DUST, Color.fromRGB(Integer.parseInt(parts[1].substring(1), 16)));
        }
        Particle particle;
        try {
            particle = Particle.valueOf(parts[0].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("'" + parts[0] + "' isn't a particle");
        }
        if (particle.getDataType() != Void.class) {
            throw new IllegalArgumentException(particle + " needs extra data; use one without, or dust");
        }
        return new Effect(particle, null);
    }

    private static String material(String name) {
        Material m = Material.matchMaterial(name);
        if (m == null) {
            throw new IllegalArgumentException("'" + name + "' isn't an item");
        }
        return m.name();
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String what) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(what + " '" + value + "' isn't one of "
                    + java.util.Arrays.stream(type.getEnumConstants()).map(x -> x.name().toLowerCase(Locale.ROOT))
                            .toList());
        }
    }

    // ------------------------------------------------------------------ lookups

    /** A cosmetic by id; numbered ones ({@code title:season-3-champion}) come filled in. */
    public Optional<Cosmetic> find(String id) {
        Cosmetic c = cosmetics.get(id);
        if (c != null) {
            return Optional.of(c);
        }
        for (Template t : templates) {
            Matcher m = t.id().matcher(id);
            if (m.matches()) {
                String n = m.group(1);
                Cosmetic tpl = t.cosmetic();
                return Optional.of(new Cosmetic(id, tpl.type(), tpl.name().replace("<n>", n),
                        tpl.display().replace("<n>", n), tpl.rarity(), tpl.obtain(), 0, null, tpl.icon(),
                        tpl.hint(), tpl.nameColor(), tpl.particle()));
            }
        }
        return Optional.empty();
    }

    /** Whether {@code id} names a cosmetic, numbered ones included. */
    public boolean exists(String id) {
        return find(id).isPresent();
    }

    /** Every fixed (not numbered) cosmetic of a type, in file order. */
    public List<Cosmetic> ofType(CosmeticType type) {
        return cosmetics.values().stream().filter(c -> c.type() == type).toList();
    }

    public List<Cosmetic> all() {
        return List.copyOf(cosmetics.values());
    }

    /** The Featured Shop pool: every cosmetic only sold while featured. */
    public List<String> featuredPool() {
        return cosmetics.values().stream().filter(c -> c.obtain() == Obtain.FEATURED).map(Cosmetic::id).toList();
    }

    public int featuredSize() {
        return featuredSize;
    }

    /** What the Featured Shop shows at {@code at}. */
    public List<Cosmetic> featured(Instant at) {
        return FeaturedRotation.pick(featuredPool(), FeaturedRotation.week(at), featuredSize).stream()
                .map(cosmetics::get).toList();
    }

    /** Whether the shop sells {@code c} at {@code at}. */
    public boolean onSale(Cosmetic c, Instant at) {
        return switch (c.obtain()) {
            case SHOP -> true;
            case FEATURED -> featured(at).contains(c);
            default -> false;
        };
    }

    public Map<Rank, RankPass> rankPasses() {
        return rankPasses;
    }
}
