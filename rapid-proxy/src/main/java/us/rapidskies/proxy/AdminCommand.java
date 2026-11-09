package us.rapidskies.proxy;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.slf4j.Logger;
import us.rapidskies.common.economy.Ledger;
import us.rapidskies.common.economy.Ledger.Result;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.common.profile.ProfileRepository;

/**
 * {@code /rapid bolts add|remove|balance}: the command Tebex packages run on the proxy
 * console (PLAN.md §6.3), also usable by admins.
 *
 * <p>Store packages must pass the Tebex transaction id so a retried delivery is a no-op,
 * and should make it unique per command when one package grants several things, e.g.
 * {@code rapid bolts add {id} 1100 {transaction}:bolts}.
 */
final class AdminCommand implements SimpleCommand {

    static final String PERMISSION = "rapid.admin.bolts";

    private final ProxyServer proxy;
    private final Lang lang;
    private final Ledger bolts;
    private final ProfileRepository profiles;
    private final Logger logger;

    AdminCommand(ProxyServer proxy, Lang lang, Ledger bolts, ProfileRepository profiles, Logger logger) {
        this.proxy = proxy;
        this.lang = lang;
        this.bolts = bolts;
        this.profiles = profiles;
        this.logger = logger;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();
        if (args.length < 3 || !args[0].equalsIgnoreCase("bolts")) {
            lang.send(source, "admin.usage");
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        String target = args[2];
        try {
            Optional<Target> player = resolve(target);
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

    private void change(CommandSource source, Target player, boolean add, String[] args) throws SQLException {
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

    private record Target(UUID uuid, String name) {
    }

    /** A UUID (what Tebex sends), an online player, or anyone who has joined before. */
    private Optional<Target> resolve(String input) throws SQLException {
        try {
            UUID uuid = UUID.fromString(input);
            String name = proxy.getPlayer(uuid).map(Player::getUsername)
                    .or(() -> lookupName(uuid)).orElse(input);
            return Optional.of(new Target(uuid, name));
        } catch (IllegalArgumentException notUuid) {
            Optional<Player> online = proxy.getPlayer(input);
            if (online.isPresent()) {
                return Optional.of(new Target(online.get().getUniqueId(), online.get().getUsername()));
            }
            return profiles.findByName(input).map(p -> new Target(p.uuid(), p.name()));
        }
    }

    private Optional<String> lookupName(UUID uuid) {
        try {
            return profiles.find(uuid).map(p -> p.name());
        } catch (SQLException e) {
            return Optional.empty();
        }
    }

    private static TagResolver name(Target t) {
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
            case 0, 1 -> List.of("bolts");
            case 2 -> List.of("add", "remove", "balance");
            case 3 -> proxy.getAllPlayers().stream().map(Player::getUsername).toList();
            default -> List.of();
        };
        String typed = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return CompletableFuture.completedFuture(
                options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(typed)).toList());
    }
}
