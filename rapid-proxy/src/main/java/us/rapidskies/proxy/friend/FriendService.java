package us.rapidskies.proxy.friend;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.slf4j.Logger;
import us.rapidskies.common.db.Database;
import us.rapidskies.common.friend.FriendRepository;
import us.rapidskies.common.friend.FriendRepository.AcceptResult;
import us.rapidskies.common.friend.FriendRepository.Entry;
import us.rapidskies.common.friend.FriendRepository.RequestResult;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.common.profile.PlayerProfile;
import us.rapidskies.common.profile.ProfileRepository;
import us.rapidskies.proxy.queue.QueueService;
import us.rapidskies.proxy.queue.QueueTarget;
import us.rapidskies.proxy.rank.ProxyRanks;
import us.rapidskies.proxy.session.VanishedStaff;

/**
 * Friends (PLAN.md §7): requests, the list with who's online where, join/leave notices,
 * and joining a friend's server. List size depends on rank (PLAN.md §6.1).
 *
 * <p>Online players' friend ids are cached so join and leave notices need no query.
 * Vanished staff count as offline to anyone who can't see them ({@link VanishedStaff}).
 * Everything that touches the database runs on {@link Database#async}.
 */
public final class FriendService {

    static final int PAGE_SIZE = 10;

    private final ProxyServer proxy;
    private final Lang lang;
    private final FriendSettings settings;
    private final ProxyRanks ranks;
    private final QueueService queue;
    private final Database db;
    private final FriendRepository friends;
    private final ProfileRepository profiles;
    private final Logger logger;
    private final VanishedStaff vanish;
    private final Map<UUID, Set<UUID>> online = new ConcurrentHashMap<>();

    public FriendService(ProxyServer proxy, Lang lang, FriendSettings settings, ProxyRanks ranks, QueueService queue,
                         Database db, ProfileRepository profiles, VanishedStaff vanish, Logger logger) {
        this.proxy = proxy;
        this.lang = lang;
        this.settings = settings;
        this.ranks = ranks;
        this.queue = queue;
        this.db = db;
        this.friends = new FriendRepository(db);
        this.profiles = profiles;
        this.logger = logger;
        this.vanish = vanish;
    }

    private record Target(UUID uuid, String name) {
    }

    private record LoginData(List<Entry> friends, List<Entry> requests) {
    }

    // ------------------------------------------------------------------ events

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        db.async(() -> new LoginData(friends.friends(id), friends.incoming(id))).thenAccept(data -> {
            if (!player.isActive()) {
                return;
            }
            Set<UUID> ids = ConcurrentHashMap.newKeySet();
            data.friends().forEach(f -> ids.add(f.uuid()));
            online.put(id, ids);
            int on = 0;
            for (UUID f : ids) {
                Optional<Player> friend = proxy.getPlayer(f);
                if (friend.isEmpty()) {
                    continue;
                }
                if (!vanish.hiddenFrom(f, player)) {
                    on++;
                }
                if (!vanish.hiddenFrom(id, friend.get())) {
                    lang.send(friend.get(), "friend.joined", name(player));
                }
            }
            if (!ids.isEmpty()) {
                lang.send(player, "friend.online-summary", Placeholder.unparsed("online", Integer.toString(on)),
                        Placeholder.unparsed("total", Integer.toString(ids.size())));
            }
            if (!data.requests().isEmpty()) {
                player.sendMessage(lang.get("friend.requests-waiting",
                                Placeholder.unparsed("count", Integer.toString(data.requests().size())))
                        .clickEvent(ClickEvent.runCommand("/friend requests")));
            }
        }).exceptionally(e -> {
            logger.warn("Couldn't load friends of {}: {}", player.getUsername(), e.getMessage());
            return null;
        });
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Set<UUID> ids = online.remove(event.getPlayer().getUniqueId());
        if (ids == null) {
            return;
        }
        for (UUID f : ids) {
            proxy.getPlayer(f).filter(p -> !vanish.hiddenFrom(event.getPlayer().getUniqueId(), p))
                    .ifPresent(p -> lang.send(p, "friend.left", name(event.getPlayer())));
        }
    }

    // ------------------------------------------------------------------ actions

    void add(Player player, String input) {
        resolve(input).thenCompose(target -> {
            if (target.isEmpty()) {
                lang.send(player, "errors.never-joined", Placeholder.unparsed("player", input));
                return CompletableFuture.completedFuture(null);
            }
            Target t = target.get();
            return ranks.of(t.uuid()).thenCompose(theirs -> db.async(() -> friends.request(player.getUniqueId(),
                            t.uuid(), limit(player), settings.limit().get(theirs.rank()))))
                    .thenAccept(result -> onRequest(player, t, result));
        }).exceptionally(e -> failed(player, "add", e));
    }

    private void onRequest(Player player, Target t, RequestResult result) {
        TagResolver who = Placeholder.unparsed("player", t.name());
        switch (result) {
            case SENT -> {
                lang.send(player, "friend.request-sent", who);
                proxy.getPlayer(t.uuid()).ifPresent(p -> p.sendMessage(lang.get("friend.request-received",
                        name(player),
                        Placeholder.component("accept", lang.get("friend.button-accept")
                                .clickEvent(ClickEvent.runCommand("/friend accept " + player.getUsername()))),
                        Placeholder.component("deny", lang.get("friend.button-deny")
                                .clickEvent(ClickEvent.runCommand("/friend deny " + player.getUsername()))))));
            }
            case ACCEPTED -> befriended(player, t);
            case ALREADY_FRIENDS -> lang.send(player, "friend.already-friends", who);
            case ALREADY_SENT -> lang.send(player, "friend.already-sent", who);
            case LIMIT_SELF -> lang.send(player, "friend.limit-self",
                    Placeholder.unparsed("limit", Integer.toString(limit(player))));
            case LIMIT_OTHER -> lang.send(player, "friend.limit-other", who);
            case SELF -> lang.send(player, "friend.self");
        }
    }

    void accept(Player player, String input) {
        resolve(input).thenCompose(target -> {
            if (target.isEmpty()) {
                lang.send(player, "errors.never-joined", Placeholder.unparsed("player", input));
                return CompletableFuture.completedFuture(null);
            }
            Target t = target.get();
            return ranks.of(t.uuid()).thenCompose(theirs -> db.async(() -> friends.accept(player.getUniqueId(),
                            t.uuid(), limit(player), settings.limit().get(theirs.rank()))))
                    .thenAccept(result -> {
                        TagResolver who = Placeholder.unparsed("player", t.name());
                        switch (result) {
                            case ACCEPTED -> befriended(player, t);
                            case NO_REQUEST -> lang.send(player, "friend.no-request", who);
                            case LIMIT_SELF -> lang.send(player, "friend.limit-self",
                                    Placeholder.unparsed("limit", Integer.toString(limit(player))));
                            case LIMIT_OTHER -> lang.send(player, "friend.limit-other", who);
                        }
                    });
        }).exceptionally(e -> failed(player, "accept", e));
    }

    void deny(Player player, String input) {
        resolve(input).thenCompose(target -> {
            if (target.isEmpty()) {
                lang.send(player, "errors.never-joined", Placeholder.unparsed("player", input));
                return CompletableFuture.completedFuture(null);
            }
            Target t = target.get();
            return db.async(() -> friends.deny(player.getUniqueId(), t.uuid())).thenAccept(ok ->
                    lang.send(player, ok ? "friend.denied" : "friend.no-request", Placeholder.unparsed("player", t.name())));
        }).exceptionally(e -> failed(player, "deny", e));
    }

    void remove(Player player, String input) {
        resolve(input).thenCompose(target -> {
            if (target.isEmpty()) {
                lang.send(player, "errors.never-joined", Placeholder.unparsed("player", input));
                return CompletableFuture.completedFuture(null);
            }
            Target t = target.get();
            return db.async(() -> friends.remove(player.getUniqueId(), t.uuid())).thenAccept(ok -> {
                if (!ok) {
                    lang.send(player, "friend.not-friends", Placeholder.unparsed("player", t.name()));
                    return;
                }
                cache(player.getUniqueId(), t.uuid(), false);
                lang.send(player, "friend.removed", Placeholder.unparsed("player", t.name()));
            });
        }).exceptionally(e -> failed(player, "remove", e));
    }

    void list(Player player, int page) {
        db.async(() -> friends.friends(player.getUniqueId())).thenAccept(list -> {
            if (list.isEmpty()) {
                lang.send(player, "friend.list-empty");
                return;
            }
            // Online friends first, then by name.
            List<Entry> sorted = new ArrayList<>(list);
            sorted.sort(Comparator.comparing((Entry f) -> visible(f.uuid(), player).isEmpty())
                    .thenComparing(f -> f.name().toLowerCase(java.util.Locale.ROOT)));
            int pages = (sorted.size() + PAGE_SIZE - 1) / PAGE_SIZE;
            int p = Math.max(1, Math.min(page, pages));
            List<Component> lines = new ArrayList<>();
            lines.add(lang.get("friend.list-header",
                    Placeholder.unparsed("count", Integer.toString(sorted.size())),
                    Placeholder.unparsed("limit", Integer.toString(limit(player))),
                    Placeholder.unparsed("page", Integer.toString(p)),
                    Placeholder.unparsed("pages", Integer.toString(pages))));
            for (Entry f : sorted.subList((p - 1) * PAGE_SIZE, Math.min(sorted.size(), p * PAGE_SIZE))) {
                lines.add(line(f, player));
            }
            if (p < pages) {
                lines.add(lang.get("friend.list-next", Placeholder.unparsed("next", Integer.toString(p + 1)))
                        .clickEvent(ClickEvent.runCommand("/friend list " + (p + 1))));
            }
            player.sendMessage(Component.join(JoinConfiguration.newlines(), lines));
        }).exceptionally(e -> failed(player, "list", e));
    }

    private Component line(Entry f, Player viewer) {
        TagResolver who = Placeholder.unparsed("player", f.name());
        Optional<Player> online = visible(f.uuid(), viewer);
        Optional<String> server = online.flatMap(Player::getCurrentServer).map(c -> c.getServerInfo().getName());
        if (online.isEmpty()) {
            return lang.get("friend.list-offline", who);
        }
        String where = server.map(id -> queue.displayName(QueueTarget.server(id))).orElse("?");
        Component line = lang.get("friend.list-online", who, Placeholder.unparsed("server", where));
        if (server.isPresent() && queue.joinable(server.get())) {
            line = line.append(Component.space()).append(lang.get("friend.button-join")
                    .clickEvent(ClickEvent.runCommand("/friend join " + f.name())));
        }
        return line;
    }

    void requests(Player player) {
        db.async(() -> friends.incoming(player.getUniqueId())).thenAccept(list -> {
            if (list.isEmpty()) {
                lang.send(player, "friend.requests-empty");
                return;
            }
            List<Component> lines = new ArrayList<>();
            lines.add(lang.get("friend.requests-header", Placeholder.unparsed("count", Integer.toString(list.size()))));
            for (Entry r : list) {
                lines.add(lang.get("friend.requests-entry", Placeholder.unparsed("player", r.name()),
                        Placeholder.component("accept", lang.get("friend.button-accept")
                                .clickEvent(ClickEvent.runCommand("/friend accept " + r.name()))),
                        Placeholder.component("deny", lang.get("friend.button-deny")
                                .clickEvent(ClickEvent.runCommand("/friend deny " + r.name())))));
            }
            player.sendMessage(Component.join(JoinConfiguration.newlines(), lines));
        }).exceptionally(e -> failed(player, "requests", e));
    }

    /** Queues the player for the server a friend is on. */
    void join(Player player, String input) {
        Optional<Player> friend = proxy.getPlayer(input).filter(f -> !vanish.hiddenFrom(f.getUniqueId(), player));
        Set<UUID> mine = online.getOrDefault(player.getUniqueId(), Set.of());
        if (friend.isEmpty() || !mine.contains(friend.get().getUniqueId())) {
            lang.send(player, "friend.join-not-online", Placeholder.unparsed("player", input));
            return;
        }
        Optional<String> server = friend.get().getCurrentServer().map(c -> c.getServerInfo().getName());
        if (server.isEmpty() || !queue.joinable(server.get())) {
            lang.send(player, "friend.join-not-joinable", name(friend.get()));
            return;
        }
        queue.join(player, QueueTarget.server(server.get()));
    }

    /** Names of a player's online friends, for tab completion. */
    List<String> onlineFriendNames(Player player) {
        return online.getOrDefault(player.getUniqueId(), Set.of()).stream()
                .map(f -> visible(f, player)).flatMap(Optional::stream).map(Player::getUsername)
                .collect(Collectors.toList());
    }

    // ------------------------------------------------------------------ helpers

    /** The player if they're online and {@code viewer} may know it. */
    private Optional<Player> visible(UUID id, Player viewer) {
        return proxy.getPlayer(id).filter(p -> !vanish.hiddenFrom(id, viewer));
    }

    private void befriended(Player player, Target t) {
        cache(player.getUniqueId(), t.uuid(), true);
        lang.send(player, "friend.now-friends", Placeholder.unparsed("player", t.name()));
        proxy.getPlayer(t.uuid()).ifPresent(p -> lang.send(p, "friend.now-friends", name(player)));
    }

    /** Keeps both players' cached lists in step with a change. */
    private void cache(UUID a, UUID b, boolean friendsNow) {
        Set<UUID> sa = online.get(a);
        Set<UUID> sb = online.get(b);
        if (friendsNow) {
            if (sa != null) {
                sa.add(b);
            }
            if (sb != null) {
                sb.add(a);
            }
        } else {
            if (sa != null) {
                sa.remove(b);
            }
            if (sb != null) {
                sb.remove(a);
            }
        }
    }

    /** An online player by exact name, else anyone who has joined with that name. */
    private CompletableFuture<Optional<Target>> resolve(String input) {
        Optional<Player> p = proxy.getPlayer(input);
        if (p.isPresent()) {
            return CompletableFuture.completedFuture(Optional.of(new Target(p.get().getUniqueId(), p.get().getUsername())));
        }
        return db.async(() -> profiles.findByName(input).map((PlayerProfile pr) -> new Target(pr.uuid(), pr.name())));
    }

    private int limit(Player player) {
        return settings.limit().get(ranks.rank(player));
    }

    private Void failed(Player player, String action, Throwable e) {
        logger.error("/friend {} failed for {}", action, player.getUsername(), e);
        lang.send(player, "friend.failed");
        return null;
    }

    private static TagResolver name(Player p) {
        return Placeholder.unparsed("player", p.getUsername());
    }
}
