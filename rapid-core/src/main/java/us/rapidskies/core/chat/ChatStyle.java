package us.rapidskies.core.chat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.entity.Player;

/**
 * How a player's name looks in chat beyond the rank prefix (PLAN.md §6.1, §6.2): the
 * cosmetics engine's chat tag and name color, and the profile card shown when the name is
 * hovered. Called on the async chat thread.
 */
public interface ChatStyle {

    /** A tag before the name, with its trailing space, or empty. */
    Component tag(Player player);

    /** The name's color, or null for the format's own. */
    TextColor nameColor(Player player);

    /** The hover card, or null for none. */
    Component card(Player player);

    ChatStyle NONE = new ChatStyle() {
        @Override
        public Component tag(Player player) {
            return Component.empty();
        }

        @Override
        public TextColor nameColor(Player player) {
            return null;
        }

        @Override
        public Component card(Player player) {
            return null;
        }
    };
}
