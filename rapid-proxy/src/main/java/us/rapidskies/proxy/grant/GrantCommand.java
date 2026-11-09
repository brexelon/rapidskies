package us.rapidskies.proxy.grant;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.common.time.Durations;
import us.rapidskies.proxy.Suggest;

/**
 * {@code /grant <player> <rank> <duration|permanent> [reason]} and
 * {@code /grant <player> perm <node> <duration|permanent> [reason]}.
 *
 * <p>The console (and so the store) may end the command with {@code txn:<id>}; a delivery
 * retried with the same id is recognised and not granted twice, e.g.
 * {@code grant {id} rapidplusplus 30d Store purchase txn:{transaction}}. A package that
 * grants several things needs a distinct id for each, e.g. {@code txn:{transaction}:rank}.
 */
public final class GrantCommand implements SimpleCommand {

    private static final List<String> DURATIONS = List.of("30d", "7d", "1d", "permanent");
    /** Longer than this is "permanent" in all but name, and would overflow DATETIME eventually. */
    private static final Duration MAX = Duration.ofDays(3650);
    private static final String TXN = "txn:";

    private final ProxyServer proxy;
    private final Lang lang;
    private final GrantService grants;

    public GrantCommand(ProxyServer proxy, Lang lang, GrantService grants) {
        this.proxy = proxy;
        this.lang = lang;
        this.grants = grants;
    }

    @Override
    public void execute(Invocation invocation) {
        String[] args = invocation.arguments();
        boolean perm = args.length >= 2 && isPerm(args[1]);
        int durationAt = perm ? 3 : 2;
        if (args.length <= durationAt) {
            lang.send(invocation.source(), "grant.usage");
            return;
        }
        Optional<GrantTarget> what = perm ? GrantTarget.permission(args[2]) : GrantTarget.rank(args[1]);
        if (what.isEmpty()) {
            if (perm) {
                lang.send(invocation.source(), "grant.bad-node", Placeholder.unparsed("input", args[2]));
            } else {
                lang.send(invocation.source(), "grant.unknown-rank", Placeholder.unparsed("input", args[1]),
                        Placeholder.unparsed("ranks", String.join(", ", GrantTarget.rankNames())));
            }
            return;
        }
        String typed = args[durationAt];
        Duration duration = null;
        if (!Durations.isPermanent(typed)) {
            Optional<Duration> parsed = Durations.parse(typed).filter(d -> d.compareTo(MAX) <= 0);
            if (parsed.isEmpty()) {
                lang.send(invocation.source(), "grant.bad-duration", Placeholder.unparsed("input", typed));
                return;
            }
            duration = parsed.get();
        }
        List<String> rest = new ArrayList<>(Arrays.asList(args).subList(durationAt + 1, args.length));
        String txn = null;
        if (!(invocation.source() instanceof Player) && !rest.isEmpty() && rest.getLast().startsWith(TXN)
                && rest.getLast().length() > TXN.length()) {
            txn = rest.removeLast().substring(TXN.length());
        }
        String reason = rest.isEmpty() ? null : String.join(" ", rest);
        grants.grant(invocation.source(), args[0], what.get(), duration, reason, txn);
    }

    private static boolean isPerm(String arg) {
        return arg.equalsIgnoreCase("perm") || arg.equalsIgnoreCase("permission");
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission(GrantTarget.USE);
    }

    @Override
    public CompletableFuture<List<String>> suggestAsync(Invocation invocation) {
        String[] args = invocation.arguments();
        String typed = Suggest.last(args);
        boolean perm = args.length >= 2 && isPerm(args[1]);
        List<String> options = switch (args.length) {
            case 0, 1 -> Suggest.players(proxy, typed);
            case 2 -> {
                List<String> names = new ArrayList<>(GrantTarget.rankNames());
                names.add("perm");
                yield Suggest.matching(names, typed);
            }
            case 3 -> perm ? List.of() : Suggest.matching(DURATIONS, typed);
            case 4 -> perm ? Suggest.matching(DURATIONS, typed) : List.of();
            default -> List.of();
        };
        return Suggest.done(options);
    }
}
