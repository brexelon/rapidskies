package us.rapidskies.core.chat;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Tags other rapid-* plugins put in front of a player's chat line, before the rank prefix:
 * a Horizons town tag (rapid-claims, PLAN.md §4.1), later an island or Chapter badge.
 *
 * <p>Providers are called on the async chat thread, so they must only read thread-safe
 * state, and they should return quickly (they run once per message). A provider returns
 * empty for players it has nothing to show for. A tag carries its own trailing space.
 */
public final class ChatTags {

    private record Provider(Plugin owner, Function<Player, Optional<Component>> tag) {
    }

    private final List<Provider> providers = new CopyOnWriteArrayList<>();

    /** Adds a provider; it's removed again by {@link #unregister} (call it from onDisable). */
    public void register(Plugin owner, Function<Player, Optional<Component>> tag) {
        providers.add(new Provider(owner, tag));
    }

    public void unregister(Plugin owner) {
        providers.removeIf(p -> p.owner() == owner);
    }

    /** Every tag for {@code player}, joined, or empty. */
    public Component of(Player player) {
        Component out = Component.empty();
        for (Provider p : providers) {
            try {
                Optional<Component> tag = p.tag().apply(player);
                if (tag.isPresent()) {
                    out = out.append(tag.get());
                }
            } catch (RuntimeException e) {
                p.owner().getSLF4JLogger().warn("Chat tag for {} failed: {}", player.getName(), e.toString());
            }
        }
        return out;
    }
}
