package us.rapidskies.proxy.queue;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.proxy.Suggest;
import us.rapidskies.proxy.party.PartyService;

/**
 * {@code /play <mode>} joins the queue for a gamemode or the lobby (the hub selector does
 * the same through Redis); {@code /play} alone shows where you are in line. In a party,
 * only the leader picks where to go and the rest follow.
 */
public final class PlayCommand implements SimpleCommand {

    private final Lang lang;
    private final QueueService queue;
    private final PartyService parties;

    public PlayCommand(Lang lang, QueueService queue, PartyService parties) {
        this.lang = lang;
        this.queue = queue;
        this.parties = parties;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            lang.send(invocation.source(), "errors.players-only");
            return;
        }
        String[] args = invocation.arguments();
        if (args.length == 0) {
            queue.position(player.getUniqueId()).ifPresentOrElse(
                    pos -> lang.send(player, "queue.position",
                            Placeholder.unparsed("target", queue.displayName(pos.target())),
                            Placeholder.unparsed("place", Integer.toString(pos.place())),
                            Placeholder.unparsed("of", Integer.toString(pos.of()))),
                    () -> lang.send(player, "queue.usage",
                            Placeholder.unparsed("modes", String.join(", ", queue.settings().groups().keySet()))));
            return;
        }
        Optional<QueueTarget> target = queue.resolve(args[0]);
        if (target.isEmpty()) {
            lang.send(player, "queue.unknown-target", Placeholder.unparsed("target", args[0]));
            return;
        }
        if (parties.of(player).filter(p -> !p.isLeader(player.getUniqueId())).isPresent()) {
            lang.send(player, "queue.party-member");
            return;
        }
        queue.join(player, target.get());
    }

    @Override
    public CompletableFuture<List<String>> suggestAsync(Invocation invocation) {
        String[] args = invocation.arguments();
        return Suggest.done(args.length <= 1
                ? Suggest.matching(queue.settings().groups().keySet(), Suggest.last(args)) : List.of());
    }

    /**
     * {@code /hub} (and {@code /lobby}): {@code /play} for the lobby group. Unlike other
     * targets, party members may use it: going back to the lobby alone is always allowed and
     * keeps them in the party. When the leader uses it, the party follows as usual.
     */
    public static SimpleCommand hub(Lang lang, QueueService queue, String hubGroup) {
        return invocation -> {
            if (!(invocation.source() instanceof Player player)) {
                lang.send(invocation.source(), "errors.players-only");
                return;
            }
            queue.resolve(hubGroup).ifPresentOrElse(
                    target -> queue.join(player, target),
                    () -> lang.send(player, "queue.unknown-target", Placeholder.unparsed("target", hubGroup)));
        };
    }

    /** {@code /leavequeue}. */
    public static SimpleCommand leave(Lang lang, QueueService queue) {
        return invocation -> {
            if (invocation.source() instanceof Player player) {
                lang.send(player, queue.leave(player) ? "queue.left" : "queue.not-queued");
            } else {
                lang.send(invocation.source(), "errors.players-only");
            }
        };
    }
}
