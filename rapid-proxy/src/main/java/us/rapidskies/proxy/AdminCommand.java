package us.rapidskies.proxy;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.slf4j.Logger;
import us.rapidskies.common.economy.Ledger;
import us.rapidskies.common.economy.Ledger.Result;
import us.rapidskies.common.grant.Grant;
import us.rapidskies.common.redis.Channels;
import us.rapidskies.common.store.StoreTransactions;
import us.rapidskies.proxy.chat.StaffChatCommand;
import us.rapidskies.proxy.grant.GrantService;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.common.profile.ProfileRepository;
import us.rapidskies.proxy.PlayerLookup.Found;

/**
 * {@code /rapid bolts add|remove|balance}: the command Tebex packages run on the proxy
 * console (PLAN.md §6.3), also usable by admins.
 *
 * <p>Store packages must pass the Tebex transaction id so a retried delivery is a no-op,
 * and should make it unique per command when one package grants several things, e.g.
 * {@code rapid bolts add {id} 1100 {transaction}:bolts}. Console deliveries with an id are
 * recorded in {@code store_transactions}.
 *
 * <p>{@code /rapid chargeback <player> <transaction> [refund]} is Tebex's chargeback (and
 * refund) command: it revokes every grant the transaction made, takes back the Bolts it
 * delivered (as many as are left; the shortfall is reported), marks the transaction and
 * alerts staff for review. Running it again changes nothing.
 */
final class AdminCommand implements SimpleCommand {

    static final String PERMISSION = "rapid.admin.bolts";

    private final ProxyServer proxy;
    private final Lang lang;
    private final Ledger bolts;
    private final PlayerLookup lookup;
    private final StoreTransactions store;
    private final GrantService grants;
    private final StaffChatCommand staffChat;
    private final Logger logger;

    AdminCommand(ProxyServer proxy, Lang lang, Ledger bolts, ProfileRepository profiles, StoreTransactions store,
                 GrantService grants, StaffChatCommand staffChat, Logger logger) {
        this.proxy = proxy;
        this.lang = lang;
        this.bolts = bolts;
        this.lookup = new PlayerLookup(proxy, profiles);
        this.store = store;
        this.grants = grants;
        this.staffChat = staffChat;
        this.logger = logger;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();
        if (args.length >= 3 && args[0].equalsIgnoreCase("chargeback")) {
            try {
                Optional<Found> player = lookup.find(args[1]);
                if (player.isEmpty()) {
                    lang.send(source, "admin.unknown-player", Placeholder.unparsed("player", args[1]));
                    return;
                }
                chargeback(source, player.get(), args[2], args.length >= 4 && args[3].equalsIgnoreCase("refund"));
            } catch (SQLException | RuntimeException e) {
                logger.error("/rapid {} failed", String.join(" ", args), e);
                lang.send(source, "admin.failed");
            }
            return;
        }
        if (args.length < 3 || !args[0].equalsIgnoreCase("bolts")) {
            lang.send(source, "admin.usage");
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        String target = args[2];
        try {
            Optional<Found> player = lookup.find(target);
            if (player.isEmpty()) {
                lang.send(source, "admin.unknown-player", Placeholder.unparsed("player", target));
                return;
            }
            switch (action) {
                case "balance" -> lang.send(source, "admin.bolts-balance", name(player.get()),
                        Placeholder.unparsed("balance", Long.toString(bolts.balance(player.get().uuid()))));
                case "add", "remove" -> change(source, player.get(), action.equals("add"), args);
                default -> lang.send(source, "admin.usage");
            }
        } catch (SQLException e) {
            logger.error("/rapid {} failed", String.join(" ", args), e);
            lang.send(source, "admin.failed");
        }
    }

    private void change(CommandSource source, Found player, boolean add, String[] args) throws SQLException {
        if (args.length < 4) {
            lang.send(source, "admin.usage");
            return;
        }
        long amount;
        try {
            amount = Long.parseLong(args[3]);
        } catch (NumberFormatException e) {
            amount = -1;
        }
        if (amount <= 0) {
            lang.send(source, "admin.bad-amount", Placeholder.unparsed("amount", args[3]));
            return;
        }
        String txn = args.length >= 5 ? args[4] : null;
        String by = source instanceof Player p ? p.getUsername() : "console";
        // Console with a transaction id is the store; anything else is a manual staff change.
        String reason = txn != null && by.equals("console") ? "store" : "admin:" + by;
        Result result = add
                ? bolts.credit(player.uuid(), amount, reason, "proxy", txn)
                : bolts.debit(player.uuid(), amount, reason, "proxy", txn);
        logger.info("Bolts {} {} for {} by {} (txn {}): {}", add ? "+" : "-", amount, player.name(), by, txn, result);

        switch (result) {
            case Result.Applied applied -> {
                lang.send(source, "admin.bolts-applied", name(player),
                        Placeholder.unparsed("balance", Long.toString(applied.balance())),
                        Placeholder.unparsed("delta", (add ? "+" : "-") + amount));
                if (add && txn != null && by.equals("console")) {
                    try {
                        store.delivered(txn, player.uuid(), "bolts:" + amount);
                    } catch (SQLException e) {
                        logger.warn("Couldn't record store transaction {}: {}", txn, e.getMessage());
                    }
                }
                if (add) {
                    final long credited = amount;
                    proxy.getPlayer(player.uuid()).ifPresent(p -> lang.send(p, "bolts.received",
                            Placeholder.unparsed("amount", Long.toString(credited)),
                            Placeholder.unparsed("balance", Long.toString(applied.balance()))));
                }
            }
            case Result.Duplicate dup -> lang.send(source, "admin.bolts-duplicate",
                    Placeholder.unparsed("txn", txn == null ? "" : txn),
                    Placeholder.unparsed("balance", Long.toString(dup.balance())));
            case Result.Insufficient low -> lang.send(source, "admin.bolts-insufficient", name(player),
                    Placeholder.unparsed("balance", Long.toString(low.balance())));
        }
    }

    private void chargeback(CommandSource source, Found player, String txn, boolean refund) throws SQLException {
        String base = StoreTransactions.base(txn);
        String why = (refund ? "Refund " : "Chargeback ") + base;
        int revoked = 0;
        for (Grant g : grants.byStoreTransaction(base)) {
            if (g.player().equals(player.uuid()) && !g.revoked()) {
                grants.revoke(source, g.id(), why);
                revoked++;
            }
        }
        long delivered = bolts.byTxnPrefix(base).stream()
                .filter(e -> e.player().equals(player.uuid()) && e.delta() > 0)
                .mapToLong(Ledger.Entry::delta).sum();
        long balance = bolts.balance(player.uuid());
        long take = Math.min(delivered, balance);
        boolean repeat = false;
        long taken = 0;
        if (take > 0) {
            Result r = bolts.debit(player.uuid(), take, refund ? "refund" : "chargeback", "proxy", "chargeback:" + base);
            if (r instanceof Result.Applied) {
                taken = take;
            } else if (r instanceof Result.Duplicate) {
                repeat = true;
            }
        }
        long shortfall = repeat ? 0 : delivered - taken;
        StoreTransactions.Status status = refund ? StoreTransactions.Status.REFUNDED : StoreTransactions.Status.CHARGEBACK;
        store.mark(base, player.uuid(), status, revoked + " grants revoked, " + taken + " Bolts taken"
                + (shortfall > 0 ? ", " + shortfall + " Bolts short" : ""));
        TagResolver[] tags = {
            Placeholder.unparsed("player", player.name()),
            Placeholder.unparsed("txn", base),
            Placeholder.unparsed("kind", refund ? "refund" : "chargeback"),
            Placeholder.unparsed("revoked", Integer.toString(revoked)),
            Placeholder.unparsed("taken", Long.toString(taken)),
            Placeholder.unparsed("short", Long.toString(shortfall))};
        lang.send(source, repeat && revoked == 0 ? "admin.chargeback-repeat" : "admin.chargeback-done", tags);
        if (!repeat || revoked > 0) {
            // Staff review every chargeback (PLAN.md §6.3); a shortfall means the Bolts were spent.
            staffChat.alert(new Channels.StaffAlert("proxy", lang.raw(shortfall > 0
                    ? "admin.chargeback-alert-short" : "admin.chargeback-alert")
                    .replace("<player>", player.name()).replace("<txn>", base)
                    .replace("<kind>", refund ? "refund" : "chargeback")
                    .replace("<revoked>", Integer.toString(revoked)).replace("<taken>", Long.toString(taken))
                    .replace("<short>", Long.toString(shortfall))));
        }
        logger.info("{} of {} for {}: {} grants revoked, {} Bolts taken, {} short", refund ? "Refund" : "Chargeback",
                base, player.name(), revoked, taken, shortfall);
    }

    private static TagResolver name(Found t) {
        return Placeholder.unparsed("player", t.name());
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission(PERMISSION);
    }

    @Override
    public CompletableFuture<List<String>> suggestAsync(Invocation invocation) {
        String[] args = invocation.arguments();
        List<String> options = switch (args.length) {
            case 0, 1 -> List.of("bolts", "chargeback");
            case 2 -> args[0].equalsIgnoreCase("chargeback")
                    ? proxy.getAllPlayers().stream().map(Player::getUsername).toList()
                    : List.of("add", "remove", "balance");
            case 3 -> args[0].equalsIgnoreCase("chargeback") ? List.of()
                    : proxy.getAllPlayers().stream().map(Player::getUsername).toList();
            default -> List.of();
        };
        String typed = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return CompletableFuture.completedFuture(
                options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(typed)).toList());
    }
}
