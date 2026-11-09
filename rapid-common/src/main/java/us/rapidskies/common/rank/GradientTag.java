package us.rapidskies.common.rank;

import java.util.List;
import java.util.PrimitiveIterator;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.Modifying;

/**
 * Colors the text inside a {@code <plus>} tag along a gradient, one color per character, the
 * way MiniMessage's own {@code <gradient>} does. Nested text that sets its own color keeps it
 * (but still takes up its place in the gradient).
 *
 * <p>Holds per-use state, so every {@code <plus>} occurrence needs a new instance.
 */
final class GradientTag implements Modifying {

    private final List<TextColor> stops;
    private int length;
    private int index;

    GradientTag(List<TextColor> stops) {
        if (stops.isEmpty()) {
            throw new IllegalArgumentException("a gradient needs at least one color");
        }
        this.stops = stops;
    }

    @Override
    public Component apply(Component current, int depth) {
        // MiniMessage calls this for the tag's root first (depth 0, with every child attached),
        // then for each descendant in order, appending the children itself.
        if (depth == 0) {
            length = codePoints(current);
            index = 0;
        }
        Component bare = current.children(List.of());
        if (!(current instanceof TextComponent text) || text.content().isEmpty()) {
            return bare;
        }
        String content = text.content();
        if (current.color() != null) {
            index += content.codePointCount(0, content.length());
            return bare;
        }
        TextComponent.Builder out = Component.text();
        for (PrimitiveIterator.OfInt it = content.codePoints().iterator(); it.hasNext(); ) {
            out.append(Component.text(Character.toString(it.nextInt()), current.style().color(at(index++, length))));
        }
        return out.build();
    }

    /** The color of character {@code i} of {@code n}. */
    TextColor at(int i, int n) {
        if (stops.size() == 1 || n <= 1) {
            return stops.get(0);
        }
        float t = Math.min(1f, (float) i / (n - 1)) * (stops.size() - 1);
        int segment = Math.min((int) t, stops.size() - 2);
        return TextColor.lerp(t - segment, stops.get(segment), stops.get(segment + 1));
    }

    private static int codePoints(Component c) {
        int n = c instanceof TextComponent t ? t.content().codePointCount(0, t.content().length()) : 0;
        for (Component child : c.children()) {
            n += codePoints(child);
        }
        return n;
    }
}
