package us.rapidskies.essentials.teleport;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import us.rapidskies.essentials.RapidEssentials;
import us.rapidskies.essentials.teleport.TpaRequests.Direction;
import us.rapidskies.essentials.teleport.TpaRequests.Request;

/**
 * {@code /tpa}, {@code /tpahere}, {@code /tpaccept}, {@code /tpdeny}, {@code /tpacancel} and
 * {@code /tptoggle}, between players on this server. Vanished staff can't be asked (they
 * aren't "here" for players). Accepting moves the player through {@link Teleports}, so their
 * guards, cooldown and warmup apply to whoever moves. {@code /tptoggle} lasts until the
 * player leaves the server.
 *
 * <p>Main thread only.
 */
public final class TpaService implements Listener {

    private final RapidEssentials plugin;
    private final TpaRequests requests = new TpaRequests();
    private final Set<UUID> refusing = new HashSet<>();

    public TpaService(RapidEssentials plugin) {
        this.plugin = plugin;
    }

    private long ttlMillis() {
        return plugin.settings().tpa().expireSeconds() * 1000L;
    }

    /** A player on this server that {@code viewer} can see, or null. */
    private Player visible(Player viewer, String name) {
        Player p = Bukkit.getPlayerExact(name);
        return p != null && plugin.core().staffMode().vanish().canSee(viewer, p) ? p : null;
    }

    public void request(Player from, String targetName, Direction direction) {
        Player to = visible(from, targetName);
        if (to == null) {
            plugin.lang().send(from, "tpa.not-here", Placeholder.unparsed("player", targetName));
            return;
        }
        if (to.equals(from)) {
            plugin.lang().send(from, "tpa.self");
            return;
        }
        if (refusing.contains(to.getUniqueId())) {
            plugin.lang().send(from, "tpa.refusing", Placeholder.unparsed("player", to.getName()));
            return;
        }
        // The sender moves on a /tpa: refuse now rather than after the other player accepts.
        if (direction == Direction.THERE && !plugin.teleports().mayStart(from, TeleportKind.TPA)) {
            return;
        }
        requests.send(from.getUniqueId(), to.getUniqueId(), direction, System.currentTimeMillis(), ttlMillis());
        String seconds = Integer.toString(plugin.settings().tpa().expireSeconds());
        boolean there = direction == Direction.THERE;
        plugin.lang().send(from, there ? "tpa.sent" : "tpa.sent-here", Placeholder.unparsed("player", to.getName()),
                Placeholder.unparsed("seconds", seconds), button("cancel", "/tpacancel"));
        plugin.lang().send(to, there ? "tpa.received" : "tpa.received-here",
                Placeholder.unparsed("player", from.getName()), Placeholder.unparsed("seconds", seconds),
                button("accept", "/tpaccept " + from.getName()), button("deny", "/tpdeny " + from.getName()));
    }

    /** {@code <name>...</name>} in a message becomes a button that runs {@code command}. */
    private static TagResolver button(String name, String command) {
        return Placeholder.styling(name, ClickEvent.runCommand(command));
    }

    public void accept(Player to, String fromName) {
        Optional<Request> taken = take(to, fromName);
        if (taken.isEmpty()) {
            return;
        }
        Request request = taken.get();
        Player sender = Bukkit.getPlayer(request.from());
        Player mover = Bukkit.getPlayer(request.mover());
        Player anchor = Bukkit.getPlayer(request.anchor());
        if (sender == null || mover == null || anchor == null) {
            plugin.lang().send(to, "tpa.none");
            return;
        }
        if (!plugin.teleports().mayStart(mover, TeleportKind.TPA)) {
            // The mover was told why; tell the other side too.
            Player other = mover.equals(to) ? sender : to;
            plugin.lang().send(other, "tpa.blocked", Placeholder.unparsed("player", mover.getName()));
            return;
        }
        plugin.lang().send(to, "tpa.you-accepted", Placeholder.unparsed("player", sender.getName()));
        plugin.lang().send(sender, "tpa.accepted", Placeholder.unparsed("player", to.getName()));
        plugin.teleports().teleport(mover, TeleportKind.TPA, () -> anchor.isOnline() ? anchor.getLocation() : null,
                false, () -> plugin.lang().send(mover, "tpa.arrived", Placeholder.unparsed("player", anchor.getName())));
    }

    public void deny(Player to, String fromName) {
        Optional<Request> taken = take(to, fromName);
        if (taken.isEmpty()) {
            return;
        }
        Player sender = Bukkit.getPlayer(taken.get().from());
        String name = sender == null ? fromName : sender.getName();
        plugin.lang().send(to, "tpa.you-denied", Placeholder.unparsed("player", name == null ? "?" : name));
        if (sender != null) {
            plugin.lang().send(sender, "tpa.denied", Placeholder.unparsed("player", to.getName()));
        }
    }

    /** The request to take for {@code /tpaccept} or {@code /tpdeny}, telling {@code to} if there's none. */
    private Optional<Request> take(Player to, String fromName) {
        long now = System.currentTimeMillis();
        if (fromName == null) {
            Optional<Request> newest = requests.take(to.getUniqueId(), null, now);
            if (newest.isEmpty()) {
                plugin.lang().send(to, "tpa.none");
            }
            return newest;
        }
        Player from = Bukkit.getPlayerExact(fromName);
        Optional<Request> named = from == null ? Optional.empty()
                : requests.take(to.getUniqueId(), from.getUniqueId(), now);
        if (named.isEmpty()) {
            plugin.lang().send(to, "tpa.none-from", Placeholder.unparsed("player", fromName));
        }
        return named;
    }

    public void cancel(Player from) {
        Optional<Request> cancelled = requests.cancel(from.getUniqueId(), System.currentTimeMillis());
        if (cancelled.isEmpty()) {
            plugin.lang().send(from, "tpa.nothing-to-cancel");
            return;
        }
        Player to = Bukkit.getPlayer(cancelled.get().to());
        plugin.lang().send(from, "tpa.cancelled", Placeholder.unparsed("player", to == null ? "?" : to.getName()));
        if (to != null) {
            plugin.lang().send(to, "tpa.cancelled-by", Placeholder.unparsed("player", from.getName()));
        }
    }

    public void toggle(Player player) {
        if (refusing.remove(player.getUniqueId())) {
            plugin.lang().send(player, "tpa.toggled-on");
        } else {
            refusing.add(player.getUniqueId());
            plugin.lang().send(player, "tpa.toggled-off");
        }
    }

    /** Names of the players with an open request to {@code to}, for tab completion. */
    public List<String> pendingNames(Player to) {
        return requests.pending(to.getUniqueId(), System.currentTimeMillis()).stream()
                .map(r -> Bukkit.getPlayer(r.from()))
                .filter(p -> p != null)
                .map(Player::getName)
                .toList();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        requests.forget(id);
        refusing.remove(id);
    }
}
