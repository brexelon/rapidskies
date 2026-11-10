package us.rapidskies.core.chat;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
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
 */
public final class ChatListener implements Listener {

    private final RankDisplay ranks;
    private final ChatTags tags;
    private final Lang lang;

    public ChatListener(RankDisplay ranks, ChatTags tags, Lang lang) {
        this.ranks = ranks;
        this.tags = tags;
        this.lang = lang;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        RankDisplay.Display display = ranks.of(event.getPlayer());
        String format = display.staff().isPresent() ? "chat.staff"
                : display.rank() == Rank.DEFAULT ? "chat.default" : "chat.ranked";
        Component prefix = ranks.prefix(display);
        // Other plugins' tags (a Horizons town tag) go in front of the whole line, so the
        // chat formats in lang stay the same on every server.
        Component before = tags.of(event.getPlayer());
        // The format is resolved once per message, not once per viewer.
        event.renderer(ChatRenderer.viewerUnaware((source, displayName, message) -> before.append(lang.get(format,
                Placeholder.component("rank", prefix),
                Placeholder.unparsed("player", source.getName()),
                Placeholder.component("message", message)))));
    }
}
