package us.rapidskies.proxy.grant;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.proxy.Suggest;

/**
 * {@code /grants <player> [page]} lists a player's grants, newest first (hover a line for
 * the details); {@code /grants revoke <id> [reason]} revokes one. A player who is actually
 * named "revoke" can be looked up by UUID.
 */
public final class GrantsCommand implements SimpleCommand {

    private final ProxyServer proxy;
    private final Lang lang;
    private final GrantService grants;

    public GrantsCommand(ProxyServer proxy, Lang lang, GrantService grants) {
        this.proxy = proxy;
        this.lang = lang;
        this.grants = grants;
    }

    @Override
    public void execute(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length == 0) {
            lang.send(invocation.source(), "grant.history.usage");
            return;
        }
        if (args[0].equalsIgnoreCase("revoke")) {
            if (!invocation.source().hasPermission(GrantTarget.REVOKE)) {
                lang.send(invocation.source(), "grant.denied.revoke");
                return;
            }
            long id = args.length >= 2 ? parseId(args[1]) : -1;
            if (id <= 0) {
                lang.send(invocation.source(), "grant.revoke.usage");
                return;
            }
            String reason = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : null;
            grants.revoke(invocation.source(), id, reason);
            return;
        }
        int page = 1;
        if (args.length >= 2) {
            try {
                page = Integer.parseInt(args[1]);
            } catch (NumberFormatException ignored) {
                // stay on page 1
            }
        }
        grants.history(invocation.source(), args[0], page);
    }

    /** "12" or "#12" (as /grants shows it); -1 if it's neither. */
    static long parseId(String input) {
        try {
            return Long.parseLong(input.startsWith("#") ? input.substring(1) : input);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission(GrantTarget.USE);
    }

    @Override
    public CompletableFuture<List<String>> suggestAsync(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length > 1) {
            return Suggest.done(List.of());
        }
        List<String> options = new ArrayList<>(Suggest.players(proxy, Suggest.last(args)));
        if (invocation.source().hasPermission(GrantTarget.REVOKE)) {
            options.addAll(Suggest.matching(List.of("revoke"), Suggest.last(args)));
        }
        return Suggest.done(options);
    }
}
