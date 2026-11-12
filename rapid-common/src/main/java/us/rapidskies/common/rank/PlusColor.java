package us.rapidskies.common.rank;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.IntStream;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/**
 * The color of the pluses in a rank prefix, chosen with {@code /plus} (PLAN.md §6.1).
 *
 * <p>Plain colors are named chat colors, so each choice has a clear name; dark gray and black
 * are left out as too hard to read. Rapid+++ also gets named <b>gradients</b>. Red shades are open to everyone: a rank prefix always reads
 * {@code RAPID+}, so it can't pass for the red {@code ADMIN} and {@code OWNER} prefixes.
 */
public enum PlusColor {
    WHITE(NamedTextColor.WHITE, Rank.RAPID_PLUS),
    YELLOW(NamedTextColor.YELLOW, Rank.RAPID_PLUS),
    GOLD(NamedTextColor.GOLD, Rank.RAPID_PLUS),
    AQUA(NamedTextColor.AQUA, Rank.RAPID_PLUS),
    GREEN(NamedTextColor.GREEN, Rank.RAPID_PLUS),
    LIGHT_PURPLE(NamedTextColor.LIGHT_PURPLE, Rank.RAPID_PLUS),
    RED(NamedTextColor.RED, Rank.RAPID_PLUS),

    BLUE(NamedTextColor.BLUE, Rank.RAPID_PLUS_PLUS),
    DARK_BLUE(NamedTextColor.DARK_BLUE, Rank.RAPID_PLUS_PLUS),
    DARK_AQUA(NamedTextColor.DARK_AQUA, Rank.RAPID_PLUS_PLUS),
    DARK_GREEN(NamedTextColor.DARK_GREEN, Rank.RAPID_PLUS_PLUS),
    DARK_PURPLE(NamedTextColor.DARK_PURPLE, Rank.RAPID_PLUS_PLUS),
    DARK_RED(NamedTextColor.DARK_RED, Rank.RAPID_PLUS_PLUS),

    GRAY(NamedTextColor.GRAY, Rank.RAPID_PLUS_PLUS_PLUS),

    // Gradients (Rapid+++). "+++" is three characters, so the first, middle and last colors
    // are what shows in a prefix; keep them readable.
    SUNSET(Rank.RAPID_PLUS_PLUS_PLUS, 0xFFFF55, 0xFFAA00, 0xFF55FF),
    OCEAN(Rank.RAPID_PLUS_PLUS_PLUS, 0x55FFFF, 0x0000AA),
    AURORA(Rank.RAPID_PLUS_PLUS_PLUS, 0x55FF55, 0x55FFFF, 0xFF55FF),
    FROST(Rank.RAPID_PLUS_PLUS_PLUS, 0xFFFFFF, 0x55FFFF),
    CITRUS(Rank.RAPID_PLUS_PLUS_PLUS, 0xFFFF55, 0x55FF55),
    TWILIGHT(Rank.RAPID_PLUS_PLUS_PLUS, 0x5555FF, 0xFF55FF),
    SILVER(Rank.RAPID_PLUS_PLUS_PLUS, 0xFFFFFF, 0x555555),
    CRIMSON(Rank.RAPID_PLUS_PLUS_PLUS, 0xAA0000, 0xFF5555),
    INFERNO(Rank.RAPID_PLUS_PLUS_PLUS, 0xFFFF55, 0xFFAA00, 0xAA0000),
    ROSE(Rank.RAPID_PLUS_PLUS_PLUS, 0xFF5555, 0xFF55FF),
    PEPPERMINT(Rank.RAPID_PLUS_PLUS_PLUS, 0xFF5555, 0xFFFFFF),
    BERRY(Rank.RAPID_PLUS_PLUS_PLUS, 0xFF5555, 0xAA00AA),
    FIREWORK(Rank.RAPID_PLUS_PLUS_PLUS, 0xFF5555, 0xFFFFFF, 0x5555FF),
    VOLCANO(Rank.RAPID_PLUS_PLUS_PLUS, 0x555555, 0xAA0000, 0xFFAA00);

    /** Shown when nothing is chosen, or the choice needs a higher rank than the player has now. */
    public static final PlusColor DEFAULT = YELLOW;

    private final TextColor color;
    private final List<TextColor> stops;
    private final Rank minimum;

    PlusColor(NamedTextColor color, Rank minimum) {
        this.color = color;
        this.stops = List.of();
        this.minimum = minimum;
    }

    PlusColor(Rank minimum, int... stops) {
        this.stops = IntStream.of(stops).mapToObj(TextColor::color).toList();
        this.color = this.stops.get(0);
        this.minimum = minimum;
    }

    /**
     * The color, or a gradient's first color. Used where only one color fits, such as
     * {@code %rapid_plus_color%}.
     */
    public TextColor color() {
        return color;
    }

    public boolean gradient() {
        return !stops.isEmpty();
    }

    /** A gradient's colors from first to last; empty for a plain color. */
    public List<TextColor> stops() {
        return stops;
    }

    /** The lowest rank that can use this color. */
    public Rank minimum() {
        return minimum;
    }

    /** Stored in {@code players.plus_color} and typed in {@code /plus <id>}. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public boolean availableTo(Rank rank) {
        return rank != Rank.DEFAULT && rank.atLeast(minimum);
    }

    public static List<PlusColor> availableFor(Rank rank) {
        return Arrays.stream(values()).filter(c -> c.availableTo(rank)).toList();
    }

    public static Optional<PlusColor> byId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String key = id.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        return Arrays.stream(values()).filter(c -> c.id().equals(key)).findFirst();
    }

    /**
     * The color a player's prefix actually shows. The stored choice is never overwritten
     * when a rank lapses: it simply isn't shown until the rank allows it again.
     */
    public static PlusColor effective(PlusColor chosen, Rank rank) {
        return chosen != null && chosen.availableTo(rank) ? chosen : DEFAULT;
    }

    /**
     * {@code <plus>...</plus>} tag for the rank prefix templates in the lang bundle: the color,
     * or the gradient spread over the enclosed text.
     */
    public TagResolver tag() {
        if (!gradient()) {
            return TagResolver.resolver("plus", Tag.styling(color));
        }
        return TagResolver.resolver("plus", (args, ctx) -> new GradientTag(stops));
    }
}
