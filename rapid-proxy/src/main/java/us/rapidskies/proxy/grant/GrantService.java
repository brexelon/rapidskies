package us.rapidskies.proxy.grant;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.luckperms.api.model.user.User;
import org.slf4j.Logger;
import us.rapidskies.common.grant.Grant;
import us.rapidskies.common.grant.GrantRepository;
import us.rapidskies.common.grant.GrantRepository.Duplicate;
import us.rapidskies.common.grant.GrantRepository.InsertResult;
import us.rapidskies.common.grant.GrantRepository.Inserted;
import us.rapidskies.common.grant.GrantRepository.NewGrant;
import us.rapidskies.common.grant.GrantTerms;
import us.rapidskies.common.grant.GrantTerms.Placement;
import us.rapidskies.common.grant.GrantTerms.Revocation;
import us.rapidskies.common.grant.Hold;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.common.store.StoreTransactions;
import us.rapidskies.common.rank.StaffRole;
import us.rapidskies.common.time.Durations;
import us.rapidskies.proxy.PlayerLookup;
import us.rapidskies.proxy.PlayerLookup.Found;
import us.rapidskies.proxy.chat.StaffChatCommand;
import us.rapidskies.proxy.rank.ProxyRanks;

/**
 * Grants ranks and permissions without anyone typing LuckPerms commands (PLAN.md §6.3, §9).
 * Each grant is a {@code grants} row (who, what, how long, why) and LuckPerms holds the
 * result as a temporary or permanent node, so everything that reads LuckPerms groups (chat
 * prefixes, TAB, party size, queue priority, other plugins) sees it with no extra wiring.
 * Timed grants stack; see {@link GrantTerms} for exactly how granting and revoking move time.
 *
 * <p>Blocking (database and LuckPerms storage): call from command threads. Grants for the
 * same player are serialized, since each one reads the current LuckPerms time to place
 * itself. There is one proxy, so an in-memory lock is enough.
 */
public final class GrantService {

    static final int PAGE_SIZE = 8;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);
    private static final String CONSOLE = "Console";

    private final ProxyServer proxy;
    private final Lang lang;
    private final GrantRepository grants;
    private final PlayerLookup players;
    private final LuckPermsHolds holds;
    private final ProxyRanks ranks;
    private final Logger logger;
    private final Clock clock;
    private final ConcurrentHashMap<UUID, Object> locks = new ConcurrentHashMap<>();
    private volatile StoreTransactions store;

    GrantService(ProxyServer proxy, Lang lang, GrantRepository grants, PlayerLookup players, LuckPermsHolds holds,
            ProxyRanks ranks, Logger logger, Clock clock) {
        this.proxy = proxy;
        this.lang = lang;
        this.grants = grants;
        this.players = players;
        this.holds = holds;
        this.ranks = ranks;
        this.logger = logger;
        this.clock = clock;
    }

    /** Where store deliveries ({@code txn:} grants from the console) are recorded (PLAN.md §6.3). */
    public void store(StoreTransactions store) {
        this.store = store;
    }

    public static GrantService create(ProxyServer proxy, Lang lang, GrantRepository grants, PlayerLookup players,
            net.luckperms.api.LuckPerms luckPerms, ProxyRanks ranks, Logger logger) {
        return new GrantService(proxy, lang, grants, players, new LuckPermsHolds(luckPerms), ranks, logger,
                Clock.systemUTC());
    }

    /**
     * @param duration null for permanent
     * @param reason   may be null
     * @param txn      store transaction id (console only), may be null
     */
    public void grant(CommandSource source, String playerInput, GrantTarget what, Duration duration, String reason,
            String txn) {
        try {
            Optional<Found> found = players.find(playerInput);
            if (found.isEmpty()) {
                lang.send(source, "errors.never-joined", Placeholder.unparsed("player", playerInput));
                return;
            }
            Found target = found.get();
            if (!allowed(source, target.uuid(), what)) {
                return;
            }
            InsertResult result = insert(source, target.uuid(), what, duration, reason, txn);
            switch (result) {
                case Duplicate dup -> lang.send(source, "grant.duplicate",
                        Placeholder.unparsed("txn", txn == null ? "" : txn),
                        Placeholder.unparsed("id", Long.toString(dup.existing().id())));
                case Inserted ins -> {
                    announceGrant(source, target, ins.grant());
                    recordStore(source, target.uuid(), ins.grant(), txn);
                }
            }
        } catch (SQLException | RuntimeException e) {
            logger.error("Grant of {} {} to {} failed", what.kind().id(), what.value(), playerInput, e);
            lang.send(source, "grant.failed");
        }
    }

    /** Places the grant against LuckPerms' current time and writes it (serialized per player). */
    private InsertResult insert(CommandSource source, UUID player, GrantTarget what, Duration duration, String reason,
            String txn) throws SQLException {
        Instant now = now();
        synchronized (lock(player)) {
            User user = holds.load(player);
            Optional<Hold> current = LuckPermsHolds.hold(user, Grant.nodeKey(what.kind(), what.value()));
            Placement place = GrantTerms.place(current, duration, now);
            NewGrant row = new NewGrant(player, what.kind(), what.value(), uuid(source), name(source),
                    reason, txn, now, place.start(), place.end());
            try {
                return grants.insert(row, () -> {
                    if (!current.equals(Optional.of(place.hold()))) {
                        holds.set(user, Grant.nodeKey(what.kind(), what.value()), Optional.of(place.hold()));
                    }
                });
            } catch (SQLException | RuntimeException e) {
                refresh(player);
                throw e;
            }
        }
    }

    /**
     * A grant the network makes itself (a rank pass bought with Bolts, PLAN.md §6.1): granted
     * as the console, announced like any grant (the player, staff, the log). {@code txn} makes
     * a retry find the first grant instead of granting twice. Blocking; throws on failure.
     */
    public Grant grantSystem(UUID player, GrantTarget what, Duration duration, String reason, String txn)
            throws SQLException {
        CommandSource console = proxy.getConsoleCommandSource();
        InsertResult result = insert(console, player, what, duration, reason, txn);
        return switch (result) {
            case Duplicate dup -> dup.existing();
            case Inserted ins -> {
                Found target = players.find(player.toString()).orElse(new Found(player, player.toString()));
                announceGrant(console, target, ins.grant());
                yield ins.grant();
            }
        };
    }

    /** Store deliveries: a console grant with a store transaction id gets a store_transactions row. */
    private void recordStore(CommandSource source, UUID player, Grant g, String txn) {
        StoreTransactions s = store;
        if (s == null || txn == null || source instanceof Player) {
            return;
        }
        try {
            s.delivered(txn, player, "rank:" + g.value() + ":" + (g.permanent() ? "permanent" : Durations.format(g.length())));
        } catch (SQLException | RuntimeException e) {
            logger.warn("Couldn't record store transaction {}: {}", txn, e.getMessage());
        }
    }

    /** Grants from one store transaction ({@code txn} and {@code txn:*}), for chargebacks. */
    public List<Grant> byStoreTransaction(String txn) throws SQLException {
        return grants.byTxnPrefix(txn);
    }

    public void revoke(CommandSource source, long id, String reason) {
        try {
            Optional<Grant> found = grants.find(id);
            if (found.isEmpty()) {
                lang.send(source, "grant.revoke.not-found", Placeholder.unparsed("id", Long.toString(id)));
                return;
            }
            Grant grant = found.get();
            GrantTarget what = new GrantTarget(grant.kind(), grant.value());
            Instant now = now();
            if (!grant.live(now)) {
                lang.send(source, grant.revoked() ? "grant.revoke.already-revoked" : "grant.revoke.already-expired",
                        Placeholder.unparsed("id", Long.toString(id)));
                return;
            }
            if (!allowed(source, grant.player(), what)) {
                return;
            }
            String key = grant.nodeKey();
            boolean revoked;
            Optional<Hold> after;
            synchronized (lock(grant.player())) {
                User user = holds.load(grant.player());
                Optional<Hold> current = LuckPermsHolds.hold(user, key);
                List<Grant> others = grants.live(grant.player(), grant.kind(), grant.value(), now).stream()
                        .filter(g -> g.id() != grant.id()).toList();
                Revocation r = GrantTerms.revoke(current, grant, others, now);
                after = switch (r.change()) {
                    case KEEP -> current.filter(h -> h.liveAt(now));
                    case SET -> Optional.of(r.hold());
                    case REMOVE -> Optional.empty();
                };
                try {
                    revoked = grants.revoke(grant, uuid(source), name(source), reason, now, r.shift(), () -> {
                        if (r.change() != GrantTerms.Change.KEEP) {
                            holds.set(user, key, after);
                        }
                    });
                } catch (SQLException | RuntimeException e) {
                    refresh(grant.player());
                    throw e;
                }
            }
            if (!revoked) {
                lang.send(source, "grant.revoke.already-revoked", Placeholder.unparsed("id", Long.toString(id)));
                return;
            }
            String playerName = players.find(grant.player().toString()).map(Found::name)
                    .orElse(grant.player().toString());
            TagResolver[] tags = {
                    Placeholder.unparsed("id", Long.toString(id)),
                    Placeholder.unparsed("player", playerName),
                    Placeholder.component("what", what(what)),
                    Placeholder.unparsed("staff", name(source)),
                    Placeholder.unparsed("reason", reason == null ? "" : reason)};
            lang.send(source, "grant.revoke.done", tags);
            after.ifPresent(h -> lang.send(source, "grant.revoke.still-has", Placeholder.unparsed("player", playerName),
                    Placeholder.component("what", what(what)), Placeholder.component("term", holdTerm(h, now))));
            notifyStaff(source, lang.get(reason == null ? "grant.revoke.notice" : "grant.revoke.notice-reason", tags));
            logger.info("{} revoked grant #{} ({} {} for {}){}", name(source), id, grant.kind().id(), grant.value(),
                    playerName, reason == null ? "" : ": " + reason);
        } catch (SQLException | RuntimeException e) {
            logger.error("Revoke of grant #{} failed", id, e);
            lang.send(source, "grant.failed");
        }
    }

    /** {@code /grants <player> [page]}: newest first, with hover details and revoke buttons. */
    public void history(CommandSource source, String playerInput, int page) {
        try {
            Optional<Found> found = players.find(playerInput);
            if (found.isEmpty()) {
                lang.send(source, "errors.never-joined", Placeholder.unparsed("player", playerInput));
                return;
            }
            Found target = found.get();
            int total = grants.count(target.uuid());
            if (total == 0) {
                lang.send(source, "grant.history.empty", Placeholder.unparsed("player", target.name()));
                return;
            }
            int pages = (total + PAGE_SIZE - 1) / PAGE_SIZE;
            int p = Math.clamp(page, 1, pages);
            List<Grant> rows = grants.history(target.uuid(), PAGE_SIZE, (p - 1) * PAGE_SIZE);
            Instant now = now();
            boolean canRevoke = source.hasPermission(GrantTarget.REVOKE);

            List<Component> lines = new ArrayList<>();
            lines.add(lang.get("grant.history.header", Placeholder.unparsed("player", target.name()),
                    Placeholder.unparsed("count", Integer.toString(total)),
                    Placeholder.unparsed("page", Integer.toString(p)),
                    Placeholder.unparsed("pages", Integer.toString(pages))));
            for (Grant g : rows) {
                Component line = lang.get("grant.history.entry",
                        Placeholder.unparsed("id", Long.toString(g.id())),
                        Placeholder.component("what", what(new GrantTarget(g.kind(), g.value()))),
                        Placeholder.component("status", status(g, now)),
                        Placeholder.unparsed("by", g.grantedByName()))
                        .hoverEvent(HoverEvent.showText(details(g)));
                if (canRevoke && g.live(now)) {
                    line = line.append(Component.space()).append(lang.get("grant.history.button-revoke")
                            .clickEvent(ClickEvent.suggestCommand("/grants revoke " + g.id() + " ")));
                }
                lines.add(line);
            }
            List<Component> nav = new ArrayList<>();
            if (p > 1) {
                nav.add(lang.get("grant.history.previous")
                        .clickEvent(ClickEvent.runCommand("/grants " + target.name() + " " + (p - 1))));
            }
            if (p < pages) {
                nav.add(lang.get("grant.history.next")
                        .clickEvent(ClickEvent.runCommand("/grants " + target.name() + " " + (p + 1))));
            }
            if (!nav.isEmpty()) {
                lines.add(Component.join(JoinConfiguration.spaces(), nav));
            }
            source.sendMessage(Component.join(JoinConfiguration.newlines(), lines));
        } catch (SQLException | RuntimeException e) {
            logger.error("/grants {} failed", playerInput, e);
            lang.send(source, "grant.failed");
        }
    }

    private boolean allowed(CommandSource source, UUID target, GrantTarget what) {
        if (source instanceof Player p) {
            if (p.getUniqueId().equals(target)) {
                lang.send(source, "grant.denied.self");
                return false;
            }
        }
        boolean console = !(source instanceof Player);
        Optional<StaffRole> role = source instanceof Player p ? ranks.of(p).staff() : Optional.empty();
        Optional<String> denial = what.denial(console, source::hasPermission, role);
        denial.ifPresent(key -> lang.send(source, key, Placeholder.component("what", what(what)),
                Placeholder.unparsed("node", what.value())));
        return denial.isEmpty();
    }

    private void announceGrant(CommandSource source, Found target, Grant g) {
        GrantTarget what = new GrantTarget(g.kind(), g.value());
        TagResolver[] tags = {
                Placeholder.unparsed("id", Long.toString(g.id())),
                Placeholder.unparsed("player", target.name()),
                Placeholder.component("what", what(what)),
                Placeholder.component("term", term(g)),
                Placeholder.unparsed("ends", g.permanent() ? "" : WHEN.format(g.expiresAt())),
                Placeholder.unparsed("starts", WHEN.format(g.startsAt())),
                Placeholder.unparsed("staff", g.grantedByName()),
                Placeholder.unparsed("reason", g.reason() == null ? "" : g.reason())};
        String key = g.permanent() ? "grant.granted-permanent"
                : g.startsAt().isAfter(g.createdAt()) ? "grant.granted-stacked" : "grant.granted";
        lang.send(source, key, tags);
        if (g.kind() == Grant.Kind.RANK) {
            proxy.getPlayer(target.uuid()).ifPresent(p -> lang.send(p, "grant.received", tags));
        }
        notifyStaff(source, lang.get(g.reason() == null ? "grant.notice" : "grant.notice-reason", tags));
        logger.info("{} granted {} {} to {} ({}){}: grant #{}", g.grantedByName(), g.kind().id(), g.value(),
                target.name(), g.permanent() ? "permanent" : Durations.format(g.length()),
                g.reason() == null ? "" : " for " + g.reason(), g.id());
    }

    /** The rest of the team hears about every grant and revoke (the actor already got a reply). */
    private void notifyStaff(CommandSource actor, Component message) {
        for (Player p : proxy.getAllPlayers()) {
            if (p != actor && p.hasPermission(StaffChatCommand.PERMISSION)) {
                p.sendMessage(message);
            }
        }
    }

    Component what(GrantTarget what) {
        return what.kind() == Grant.Kind.RANK
                ? lang.get("grant.name." + what.value())
                : lang.get("grant.name.permission", Placeholder.unparsed("node", what.value()));
    }

    private Component term(Grant g) {
        return g.permanent() ? lang.get("grant.term.permanent")
                : lang.get("grant.term.timed", Placeholder.unparsed("length", Durations.format(g.length())));
    }

    private Component holdTerm(Hold h, Instant now) {
        return h.permanent() ? lang.get("grant.term.permanent")
                : lang.get("grant.term.until", Placeholder.unparsed("ends", WHEN.format(h.until())),
                        Placeholder.unparsed("left", Durations.format(Duration.between(now, h.until()))));
    }

    private Component status(Grant g, Instant now) {
        return switch (g.status(now)) {
            case ACTIVE -> g.permanent() ? lang.get("grant.status.permanent")
                    : lang.get("grant.status.active",
                            Placeholder.unparsed("left", Durations.format(Duration.between(now, g.expiresAt()))));
            case QUEUED -> lang.get("grant.status.queued",
                    Placeholder.unparsed("starts", Durations.format(Duration.between(now, g.startsAt()))));
            case EXPIRED -> lang.get("grant.status.expired");
            case REVOKED -> lang.get("grant.status.revoked",
                    Placeholder.unparsed("by", g.revokedByName() == null ? CONSOLE : g.revokedByName()));
        };
    }

    private Component details(Grant g) {
        List<TagResolver> tags = List.of(
                Placeholder.unparsed("id", Long.toString(g.id())),
                Placeholder.unparsed("when", WHEN.format(g.createdAt())),
                Placeholder.unparsed("by", g.grantedByName()),
                Placeholder.component("length", g.permanent() ? lang.get("grant.term.permanent")
                        : Component.text(Durations.format(g.length()))),
                Placeholder.unparsed("starts", WHEN.format(g.startsAt())),
                Placeholder.unparsed("ends", g.permanent() ? "" : WHEN.format(g.expiresAt())),
                Placeholder.component("reason", reasonOrNone(g.reason())),
                Placeholder.unparsed("txn", g.txn() == null ? "" : g.txn()));
        TagResolver[] t = tags.toArray(TagResolver[]::new);
        List<Component> parts = new ArrayList<>();
        parts.add(lang.get("grant.history.details", t));
        if (!g.permanent()) {
            parts.add(lang.get("grant.history.details-timed", t));
        }
        if (g.txn() != null) {
            parts.add(lang.get("grant.history.details-txn", t));
        }
        if (g.revoked()) {
            parts.add(lang.get("grant.history.details-revoked",
                    Placeholder.unparsed("when", WHEN.format(g.revokedAt())),
                    Placeholder.unparsed("by", g.revokedByName() == null ? CONSOLE : g.revokedByName()),
                    Placeholder.component("reason", reasonOrNone(g.revokeReason()))));
        }
        return Component.join(JoinConfiguration.newlines(), parts);
    }

    private Component reasonOrNone(String reason) {
        return reason == null ? lang.get("grant.no-reason") : Component.text(reason);
    }

    /** After a failed write, reload the player so LuckPerms' in-memory copy matches storage again. */
    private void refresh(UUID player) {
        try {
            holds.load(player);
        } catch (RuntimeException e) {
            logger.warn("Couldn't reload LuckPerms data for {} after a failed grant change", player, e);
        }
    }

    private Object lock(UUID player) {
        return locks.computeIfAbsent(player, k -> new Object());
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.SECONDS);
    }

    private static UUID uuid(CommandSource source) {
        return source instanceof Player p ? p.getUniqueId() : null;
    }

    private static String name(CommandSource source) {
        return source instanceof Player p ? p.getUsername() : CONSOLE;
    }
}
