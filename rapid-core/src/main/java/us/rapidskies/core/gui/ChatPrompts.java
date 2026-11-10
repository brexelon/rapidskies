package us.rapidskies.core.gui;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Typed input through chat (PLAN.md §3, cross-version rules: no anvil or sign text input for
 * core flows, and server dialogs are 1.21.6+). A plugin asks, closes its menu and tells the
 * player what to type; the player's next chat line is the answer. It's never sent to chat.
 *
 * <p>Typing {@code cancel}, leaving or the timeout ends the prompt. One prompt per player: a
 * new one replaces the last. Registered once by rapid-core; use it through
 * {@code RapidCore.get().prompts()}.
 */
public final class ChatPrompts implements Listener {

    private record Pending(Plugin owner, Consumer<String> answer, Runnable cancelled, BukkitTask timeout) {
    }

    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    /**
     * Waits for {@code player}'s next chat line. {@code answer} gets the trimmed text on the
     * main thread; {@code cancelled} (may be null) runs on the main thread after
     * {@code cancel}, the timeout, or a newer prompt. Leaving the server ends it silently.
     * Main thread.
     */
    public void ask(Plugin owner, Player player, Duration timeout, Consumer<String> answer, Runnable cancelled) {
        UUID id = player.getUniqueId();
        BukkitTask task = Bukkit.getScheduler().runTaskLater(owner, () -> {
            Pending p = pending.get(id);
            if (p != null && p.answer() == answer && pending.remove(id, p) && p.cancelled() != null) {
                p.cancelled().run();
            }
        }, Math.max(1, timeout.toMillis() / 50));
        Pending old = pending.put(id, new Pending(owner, answer, cancelled, task));
        if (old != null) {
            end(old, true);
        }
    }

    /** Whether {@code player} has a prompt waiting. */
    public boolean waiting(Player player) {
        return pending.containsKey(player.getUniqueId());
    }

    /** Drops every prompt a plugin started (its onDisable). */
    public void cancelAll(Plugin owner) {
        pending.entrySet().removeIf(e -> {
            if (e.getValue().owner() == owner) {
                e.getValue().timeout().cancel();
                return true;
            }
            return false;
        });
    }

    // LOWEST: taken before rapid-core's chat formatting (ignoreCancelled) or anything else sees it.
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Pending p = pending.remove(event.getPlayer().getUniqueId());
        if (p == null) {
            return;
        }
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        Bukkit.getScheduler().runTask(p.owner(), () -> {
            p.timeout().cancel();
            if (text.isEmpty() || text.equalsIgnoreCase("cancel")) {
                if (p.cancelled() != null) {
                    p.cancelled().run();
                }
            } else if (event.getPlayer().isOnline()) {
                p.answer().accept(text);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Pending p = pending.remove(event.getPlayer().getUniqueId());
        if (p != null) {
            p.timeout().cancel();
        }
    }

    private static void end(Pending p, boolean notify) {
        p.timeout().cancel();
        if (notify && p.cancelled() != null && p.owner().isEnabled()) {
            p.cancelled().run();
        }
    }
}
