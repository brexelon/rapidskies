package us.rapidskies.essentials.money;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import us.rapidskies.common.profile.PlayerProfile;
import us.rapidskies.common.profile.ProfileRepository;
import us.rapidskies.core.economy.ModeEconomy;
import us.rapidskies.essentials.RapidEssentials;
import us.rapidskies.essentials.command.Suggest;
import us.rapidskies.essentials.data.EssentialsRepository;

/**
 * The gamemode currency (Coins on SkyRealms, Crowns on Horizons) from the keyboard:
 * {@code /balance}, {@code /pay}, {@code /baltop} and {@code /eco}. Only registered on
 * servers with a gamemode (rapid-core's {@code server.gamemode}). Bolts are never here: they
 * can't be traded (PLAN.md §6.2), and {@code /bolts} is rapid-core's.
 *
 * <p>Every change goes through rapid-core's {@link ModeEconomy} and so its ledger: a payment
 * is one transaction (both rows or neither), with a shared transaction id linking the two.
 */
public final class MoneyCommands {

    public static final String BALANCE = "rapid.command.balance";
    public static final String BALANCE_OTHERS = "rapid.command.balance.others";
    public static final String PAY = "rapid.command.pay";
    public static final String BALTOP = "rapid.command.baltop";
    public static final String ECO = "rapid.command.eco";

    /** How long a {@code /baltop} answer is reused before reading the table again. */
    private static final long TOP_CACHE_MILLIS = 30_000;

    private record Balance(PlayerProfile profile, long balance) {
    }

    private record Top(List<EssentialsRepository.TopEntry> entries, long readAt) {
    }

    private final RapidEssentials plugin;
    private final ModeEconomy economy;
    private final EssentialsRepository repository;
    private final ProfileRepository profiles;
    private volatile Top top;

    public MoneyCommands(RapidEssentials plugin, ModeEconomy economy, EssentialsRepository repository) {
        this.plugin = plugin;
        this.economy = economy;
        this.repository = repository;
        this.profiles = new ProfileRepository(plugin.core().database());
    }

    private TagResolver amount(long amount) {
        return TagResolver.resolver(Placeholder.unparsed("amount", Money.format(amount)),
                Placeholder.unparsed("currency", economy.mode().currencyName(amount)));
    }

    // ------------------------------------------------------------------ /balance

    public BasicCommand balance() {
        return new BasicCommand() {
            @Override
            public void execute(CommandSourceStack stack, String[] args) {
                CommandSender sender = stack.getSender();
                if (args.length == 0) {
                    if (!(sender instanceof Player player)) {
                        plugin.lang().send(sender, "money.usage-balance");
                        return;
                    }
                    own(player);
                    return;
                }
                if (!sender.hasPermission(BALANCE_OTHERS)) {
                    plugin.lang().send(sender, "money.no-others");
                    return;
                }
                String name = args[0];
                plugin.core().database().async(() -> {
                    Optional<PlayerProfile> profile = profiles.findByName(name);
                    return profile.isEmpty() ? null
                            : new Balance(profile.get(), economy.balance(profile.get().uuid()));
                }).thenAccept(found -> {
                    if (found == null) {
                        plugin.lang().send(sender, "money.unknown-player", Placeholder.unparsed("player", name));
                        return;
                    }
                    plugin.lang().send(sender, "money.balance-other",
                            Placeholder.unparsed("player", found.profile().name()), amount(found.balance()));
                }).exceptionally(e -> failed(sender, e));
            }

            private void own(Player player) {
                OptionalLong cached = economy.cachedBalance(player.getUniqueId());
                if (cached.isPresent()) {
                    plugin.lang().send(player, "money.balance", amount(cached.getAsLong()));
                    return;
                }
                UUID id = player.getUniqueId();
                plugin.core().database().async(() -> economy.balance(id))
                        .thenAccept(b -> plugin.lang().send(player, "money.balance", amount(b)))
                        .exceptionally(e -> failed(player, e));
            }

            @Override
            public Collection<String> suggest(CommandSourceStack stack, String[] args) {
                return args.length <= 1 && stack.getSender().hasPermission(BALANCE_OTHERS)
                        ? Suggest.players(plugin, stack.getSender(), args) : List.of();
            }

            @Override
            public String permission() {
                return BALANCE;
            }
        };
    }

    // ------------------------------------------------------------------ /pay

    /** What a {@code /pay} came to, worked out off the main thread. */
    private sealed interface Paid {
        record Unknown() implements Paid {
        }

        record Self() implements Paid {
        }

        record Done(PlayerProfile to, ModeEconomy.Change change) implements Paid {
        }
    }

    public BasicCommand pay() {
        return new BasicCommand() {
            @Override
            public void execute(CommandSourceStack stack, String[] args) {
                if (!(stack.getSender() instanceof Player player)) {
                    plugin.lang().send(stack.getSender(), "errors.players-only");
                    return;
                }
                if (args.length != 2) {
                    plugin.lang().send(player, "money.usage-pay");
                    return;
                }
                OptionalLong parsed = Money.parse(args[1]);
                if (parsed.isEmpty()) {
                    plugin.lang().send(player, "money.invalid-amount", Placeholder.unparsed("input", args[1]));
                    return;
                }
                long value = parsed.getAsLong();
                long minimum = plugin.settings().economy().payMinimum();
                if (value < minimum) {
                    plugin.lang().send(player, "money.pay-minimum", amount(minimum));
                    return;
                }
                UUID from = player.getUniqueId();
                String name = args[0];
                // Links the debit row (txn) and the credit row (txn + "/in") in the ledger.
                String txn = "pay:" + UUID.randomUUID();
                plugin.core().database().async(() -> {
                    Optional<PlayerProfile> to = profiles.findByName(name);
                    if (to.isEmpty()) {
                        return (Paid) new Paid.Unknown();
                    }
                    if (to.get().uuid().equals(from)) {
                        return new Paid.Self();
                    }
                    return new Paid.Done(to.get(), economy.transfer(from, to.get().uuid(), value, "pay", txn));
                }).thenAccept(paid -> {
                    switch (paid) {
                        case Paid.Unknown u -> plugin.lang().send(player, "money.unknown-player",
                                Placeholder.unparsed("player", name));
                        case Paid.Self s -> plugin.lang().send(player, "money.pay-self");
                        case Paid.Done d when !d.change().success() -> plugin.lang().send(player,
                                "money.insufficient", amount(d.change().balance()));
                        case Paid.Done d -> {
                            plugin.lang().send(player, "money.paid", Placeholder.unparsed("player", d.to().name()),
                                    amount(value));
                            Player receiver = Bukkit.getPlayer(d.to().uuid());
                            if (receiver != null) {
                                plugin.lang().send(receiver, "money.received",
                                        Placeholder.unparsed("player", player.getName()), amount(value));
                            }
                        }
                    }
                }).exceptionally(e -> failed(player, e));
            }

            @Override
            public Collection<String> suggest(CommandSourceStack stack, String[] args) {
                return args.length <= 1 ? Suggest.players(plugin, stack.getSender(), args) : List.of();
            }

            @Override
            public String permission() {
                return PAY;
            }
        };
    }

    // ------------------------------------------------------------------ /baltop

    public BasicCommand baltop() {
        return new BasicCommand() {
            @Override
            public void execute(CommandSourceStack stack, String[] args) {
                CommandSender sender = stack.getSender();
                Top cached = top;
                if (cached != null && System.currentTimeMillis() - cached.readAt() < TOP_CACHE_MILLIS) {
                    show(sender, cached.entries());
                    return;
                }
                int size = plugin.settings().economy().baltopSize();
                plugin.core().database().async(() -> repository.top(economy.mode().id(), size))
                        .thenAccept(entries -> {
                            top = new Top(entries, System.currentTimeMillis());
                            show(sender, entries);
                        })
                        .exceptionally(e -> failed(sender, e));
            }

            private void show(CommandSender sender, List<EssentialsRepository.TopEntry> entries) {
                if (entries.isEmpty()) {
                    plugin.lang().send(sender, "money.top-empty");
                    return;
                }
                Component out = plugin.lang().get("money.top-header",
                        Placeholder.unparsed("currency", economy.mode().currencyName(2)));
                int rank = 1;
                for (EssentialsRepository.TopEntry e : entries) {
                    out = out.appendNewline().append(plugin.lang().get("money.top-line",
                            Placeholder.unparsed("rank", Integer.toString(rank++)),
                            Placeholder.unparsed("player", e.name()), amount(e.balance())));
                }
                sender.sendMessage(out);
            }

            @Override
            public String permission() {
                return BALTOP;
            }
        };
    }

    // ------------------------------------------------------------------ /eco

    /**
     * {@code /eco give|take <player> <amount>}: admin corrections. The ledger row's reason is
     * {@code admin:<name>}, and staff uses go to {@code staff_actions} too.
     */
    public BasicCommand eco() {
        return new BasicCommand() {
            @Override
            public void execute(CommandSourceStack stack, String[] args) {
                CommandSender sender = stack.getSender();
                String sub = args.length == 3 ? args[0].toLowerCase(java.util.Locale.ROOT) : "";
                if (!sub.equals("give") && !sub.equals("take")) {
                    plugin.lang().send(sender, "money.usage-eco");
                    return;
                }
                OptionalLong parsed = Money.parse(args[2]);
                if (parsed.isEmpty()) {
                    plugin.lang().send(sender, "money.invalid-amount", Placeholder.unparsed("input", args[2]));
                    return;
                }
                long value = parsed.getAsLong();
                String name = args[1];
                String reason = "admin:" + sender.getName();
                boolean give = sub.equals("give");
                plugin.core().database().async(() -> {
                    Optional<PlayerProfile> target = profiles.findByName(name);
                    if (target.isEmpty()) {
                        return null;
                    }
                    UUID id = target.get().uuid();
                    ModeEconomy.Change change = give ? economy.deposit(id, value, reason)
                            : economy.withdraw(id, value, reason);
                    return new Paid.Done(target.get(), change);
                }).thenAccept(done -> {
                    if (done == null) {
                        plugin.lang().send(sender, "money.unknown-player", Placeholder.unparsed("player", name));
                        return;
                    }
                    var who = Placeholder.unparsed("player", done.to().name());
                    if (!done.change().success()) {
                        plugin.lang().send(sender, "money.eco-insufficient", who, amount(done.change().balance()));
                        return;
                    }
                    plugin.lang().send(sender, give ? "money.eco-given" : "money.eco-taken", who, amount(value),
                            Placeholder.unparsed("balance", Money.format(done.change().balance())));
                    if (sender instanceof Player staff) {
                        plugin.staffLog().log(staff, "eco-" + sub, Bukkit.getPlayer(done.to().uuid()),
                                value + " " + economy.mode().id() + " " + done.to().name() + " (" + done.to().uuid()
                                        + ")");
                    }
                }).exceptionally(e -> failed(sender, e));
            }

            @Override
            public Collection<String> suggest(CommandSourceStack stack, String[] args) {
                return switch (Suggest.position(args)) {
                    case 0 -> Suggest.matching(List.of("give", "take"), args);
                    case 1 -> Suggest.players(plugin, stack.getSender(), args);
                    default -> List.of();
                };
            }

            @Override
            public String permission() {
                return ECO;
            }
        };
    }

    private Void failed(CommandSender sender, Throwable e) {
        plugin.getSLF4JLogger().warn("A money command by {} failed", sender.getName(), e);
        plugin.lang().send(sender, "money.failed");
        return null;
    }
}
