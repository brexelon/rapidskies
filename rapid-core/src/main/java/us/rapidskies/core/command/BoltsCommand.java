package us.rapidskies.core.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import us.rapidskies.common.db.Database;
import us.rapidskies.common.economy.Ledger;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.common.profile.PlayerProfile;
import us.rapidskies.common.profile.ProfileRepository;

/** {@code /bolts [player]}: Bolts balance (PLAN.md §6.2). Others' balances need a permission. */
public final class BoltsCommand implements BasicCommand {

    static final String OTHERS = "rapid.command.bolts.others";

    private final Database db;
    private final Ledger bolts;
    private final ProfileRepository profiles;
    private final Lang lang;

    public BoltsCommand(Database db, Ledger bolts, ProfileRepository profiles, Lang lang) {
        this.db = db;
        this.bolts = bolts;
        this.profiles = profiles;
        this.lang = lang;
    }

    @Override
    public void execute(CommandSourceStack stack, String[] args) {
        CommandSender sender = stack.getSender();
        if (args.length >= 1 && sender.hasPermission(OTHERS)) {
            String name = args[0];
            Player online = Bukkit.getPlayerExact(name);
            UUID onlineId = online == null ? null : online.getUniqueId();
            db.async(() -> {
                Optional<UUID> target = onlineId != null ? Optional.of(onlineId)
                        : profiles.findByName(name).map(PlayerProfile::uuid);
                return target.isEmpty() ? Optional.<Long>empty() : Optional.of(bolts.balance(target.get()));
            }).thenAccept(balance -> {
                if (balance.isEmpty()) {
                    lang.send(sender, "bolts.unknown-player", Placeholder.unparsed("player", name));
                } else {
                    lang.send(sender, "bolts.balance-other", Placeholder.unparsed("player", name),
                            Placeholder.unparsed("balance", Long.toString(balance.get())));
                }
            }).exceptionally(e -> failed(sender));
            return;
        }
        if (!(sender instanceof Player player)) {
            lang.send(sender, "errors.players-only");
            return;
        }
        bolts.balanceAsync(player.getUniqueId())
                .thenAccept(b -> lang.send(player, "bolts.balance", Placeholder.unparsed("balance", Long.toString(b))))
                .exceptionally(e -> failed(player));
    }

    private Void failed(CommandSender sender) {
        lang.send(sender, "bolts.failed");
        return null;
    }

    @Override
    public Collection<String> suggest(CommandSourceStack stack, String[] args) {
        if (args.length <= 1 && stack.getSender().hasPermission(OTHERS)) {
            String typed = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(typed)).toList();
        }
        return List.of();
    }
}
