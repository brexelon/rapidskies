package us.rapidskies.core.chat;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.common.rank.Rank;
import us.rapidskies.core.rank.RankDisplay;

/**
 * Server-side chat formatting (PLAN.md §3: backends run enforce-secure-profile=false and
 * format chat themselves). Formats come from lang {@code chat.default|ranked|staff}.
 * The typed message is inserted as a component, so players can't inject MiniMessage tags.
 * The name carries the player's cosmetics ({@link ChatStyle}): a chat tag after the rank
 * prefix, a name color, and a profile card on hover (clicking it starts a {@code /msg}).
 */
public final class ChatListener implements Listener {

    private final RankDisplay ranks;
    private final ChatTags tags;
    private final Lang lang;
    private volatile ChatStyle style = ChatStyle.NONE;

    public ChatListener(RankDisplay ranks, ChatTags tags, Lang lang) {
        this.ranks = ranks;
        this.tags = tags;
        this.lang = lang;
    }

    public void style(ChatStyle style) {
        this.style = style;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        RankDisplay.Display display = ranks.of(player);
        String format = display.staff().isPresent() ? "chat.staff"
                : display.rank() == Rank.DEFAULT ? "chat.default" : "chat.ranked";
        ChatStyle s = style;
        Component prefix = ranks.prefix(display).append(s.tag(player));
        Component name = name(player, s);
        // Other plugins' tags (a Horizons town tag) go in front of the whole line, so the
        // chat formats in lang stay the same on every server.
        Component before = tags.of(player);
        // The format is resolved once per message, not once per viewer.
        event.renderer(ChatRenderer.viewerUnaware((source, displayName, message) -> before.append(lang.get(format,
                Placeholder.component("rank", prefix),
                Placeholder.component("player", name),
                Placeholder.component("message", message)))));
    }

    private static Component name(Player player, ChatStyle s) {
        Component name = Component.text(player.getName());
        TextColor color = s.nameColor(player);
        if (color != null) {
            name = name.color(color);
        }
        Component card = s.card(player);
        if (card != null) {
            name = name.hoverEvent(HoverEvent.showText(card))
                    .clickEvent(ClickEvent.suggestCommand("/msg " + player.getName() + " "));
        }
        return name;
    }
}
