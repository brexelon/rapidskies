package us.rapidskies.proxy.party;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.proxy.party.Parties.Departure;
import us.rapidskies.proxy.party.Parties.Failure;
import us.rapidskies.proxy.party.Parties.PartyView;
import us.rapidskies.proxy.party.Parties.Result;
import us.rapidskies.proxy.queue.QueueService;
import us.rapidskies.proxy.queue.QueueSettings;
import us.rapidskies.proxy.queue.QueueTarget;
import us.rapidskies.proxy.rank.ProxyRanks;

/**
 * Parties on the proxy (PLAN.md §7): invites, chat, warp, and members following their
 * leader from server to server. Size comes from the leader's rank (PLAN.md §6.1).
 */
public final class PartyService {

    private final ProxyServer proxy;
    private final Lang lang;
    private final PartySettings settings;
    private final ProxyRanks ranks;
    private final QueueService queue;
    private final Parties parties = new Parties();
    private ScheduledTask expiry;

    public PartyService(ProxyServer proxy, Lang lang, PartySettings settings, ProxyRanks ranks, QueueService queue) {
        this.proxy = proxy;
        this.lang = lang;
        this.settings = settings;
        this.ranks = ranks;
        this.queue = queue;
    }

    public void start(Object plugin) {
        expiry = proxy.getScheduler().buildTask(plugin, this::expireInvites).repeat(2, TimeUnit.SECONDS).schedule();
    }

    public void stop() {
        if (expiry != null) {
            expiry.cancel();
        }
    }

    public Optional<PartyView> of(Player player) {
        return parties.of(player.getUniqueId());
    }

    // ------------------------------------------------------------------ actions

    void invite(Player leader, Player target) {
        Result<PartyView> r = parties.invite(leader.getUniqueId(), target.getUniqueId(), maxSize(leader),
                System.currentTimeMillis(), settings.inviteMillis());
        if (!r.ok()) {
            fail(leader, r.failure(), target.getUsername());
            return;
        }
        String seconds = Long.toString(settings.inviteMillis() / 1000);
        lang.send(leader, "party.invite-sent", name(target), Placeholder.unparsed("seconds", seconds));
        broadcast(r.value(), leader.getUniqueId(), "party.invite-sent-others", name("leader", leader), name(target));
        String leaderName = leader.getUsername();
        target.sendMessage(lang.get("party.invited", name("leader", leader), Placeholder.unparsed("seconds", seconds),
                Placeholder.component("accept", lang.get("party.button-accept")
                        .clickEvent(ClickEvent.runCommand("/party accept " + leaderName))),
                Placeholder.component("deny", lang.get("party.button-deny")
                        .clickEvent(ClickEvent.runCommand("/party deny " + leaderName)))));
    }

    void accept(Player player, Optional<Player> from) {
        Optional<UUID> member = from.map(Player::getUniqueId).or(() -> onlyInvite(player));
        if (member.isEmpty()) {
            lang.send(player, from.isEmpty() ? "party.no-invites" : "party.no-invite-from",
                    Placeholder.unparsed("player", from.map(Player::getUsername).orElse("")));
            return;
        }
        Optional<PartyView> party = parties.of(member.get());
        int max = party.flatMap(p -> proxy.getPlayer(p.leader())).map(this::maxSize).orElse(0);
        Result<PartyView> r = parties.accept(player.getUniqueId(), member.get(), max, System.currentTimeMillis());
        if (!r.ok()) {
            fail(player, r.failure(), from.map(Player::getUsername).orElse(""));
            return;
        }
        lang.send(player, "party.joined", Placeholder.unparsed("leader", nameOf(r.value().leader())));
        broadcast(r.value(), player.getUniqueId(), "party.member-joined", name(player));
        // Join the leader where they are, if it's somewhere players can go.
        proxy.getPlayer(r.value().leader()).flatMap(Player::getCurrentServer)
                .map(c -> c.getServerInfo().getName())
                .filter(id -> queue.joinable(id) && !onServer(player, id))
                .ifPresent(id -> queue.join(player, QueueTarget.server(id), QueueSettings.FOLLOW_PRIORITY, false));
    }

    void deny(Player player, Optional<Player> from) {
        Optional<UUID> member = from.map(Player::getUniqueId).or(() -> onlyInvite(player));
        Optional<PartyView> party = member.flatMap(m -> parties.deny(player.getUniqueId(), m));
        if (party.isEmpty()) {
            lang.send(player, "party.no-invites");
            return;
        }
        lang.send(player, "party.denied", Placeholder.unparsed("leader", nameOf(party.get().leader())));
        proxy.getPlayer(party.get().leader()).ifPresent(l -> lang.send(l, "party.invite-denied", name(player)));
    }

    void leave(Player player) {
        Optional<Departure> d = parties.leave(player.getUniqueId());
        if (d.isEmpty()) {
            lang.send(player, "party.not-in-party");
            return;
        }
        lang.send(player, "party.left");
        announceDeparture(d.get(), player, "party.member-left");
    }

    void kick(Player leader, Player target) {
        Result<Departure> r = parties.kick(leader.getUniqueId(), target.getUniqueId());
        if (!r.ok()) {
            fail(leader, r.failure(), target.getUsername());
            return;
        }
        lang.send(target, "party.kicked-you", name("leader", leader));
        announceDeparture(r.value(), target, "party.member-kicked");
    }

    void transfer(Player leader, Player target) {
        Result<PartyView> r = parties.transfer(leader.getUniqueId(), target.getUniqueId());
        if (!r.ok()) {
            fail(leader, r.failure(), target.getUsername());
            return;
        }
        broadcast(r.value(), null, "party.new-leader", name(target));
    }

    void disband(Player leader) {
        Result<PartyView> r = parties.disband(leader.getUniqueId());
        if (!r.ok()) {
            fail(leader, r.failure(), "");
            return;
        }
        broadcast(r.value(), null, "party.disbanded", name("leader", leader));
    }

    void list(Player player) {
        Optional<PartyView> party = parties.of(player.getUniqueId());
        if (party.isEmpty()) {
            lang.send(player, "party.not-in-party");
            return;
        }
        PartyView p = party.get();
        Player leader = proxy.getPlayer(p.leader()).orElse(null);
        int max = leader == null ? p.members().size() : maxSize(leader);
        List<Component> lines = new java.util.ArrayList<>();
        lines.add(lang.get("party.list-header", Placeholder.unparsed("count", Integer.toString(p.members().size())),
                Placeholder.unparsed("max", Integer.toString(max))));
        for (UUID m : p.members()) {
            String server = proxy.getPlayer(m).flatMap(Player::getCurrentServer)
                    .map(c -> c.getServerInfo().getName()).orElse("?");
            lines.add(lang.get(p.isLeader(m) ? "party.list-leader" : "party.list-member",
                    Placeholder.unparsed("player", nameOf(m)), Placeholder.unparsed("server", server)));
        }
        player.sendMessage(Component.join(JoinConfiguration.newlines(), lines));
    }

    void chat(Player player, String message) {
        Optional<PartyView> party = parties.of(player.getUniqueId());
        if (party.isEmpty()) {
            lang.send(player, "party.not-in-party");
            return;
        }
        broadcast(party.get(), null, "party.chat", name(player), Placeholder.unparsed("message", message));
    }

    /** The leader pulls every member to the server they're on. */
    void warp(Player leader) {
        Optional<PartyView> party = parties.of(leader.getUniqueId());
        if (party.isEmpty() || !party.get().isLeader(leader.getUniqueId())) {
            fail(leader, party.isEmpty() ? Failure.NOT_IN_PARTY : Failure.NOT_LEADER, "");
            return;
        }
        Optional<String> server = leader.getCurrentServer().map(c -> c.getServerInfo().getName());
        if (server.isEmpty() || !queue.joinable(server.get())) {
            lang.send(leader, "party.warp-not-here");
            return;
        }
        pull(party.get(), server.get(), "party.warped");
        lang.send(leader, "party.warp-sent");
    }

    // ------------------------------------------------------------------ events

    /** Members follow the leader into game servers and hubs (follow-groups), never into staff servers. */
    @Subscribe
    public void onServerConnected(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        Optional<PartyView> party = parties.of(player.getUniqueId());
        if (party.isEmpty() || !party.get().isLeader(player.getUniqueId())) {
            return;
        }
        player.getCurrentServer().map(c -> c.getServerInfo().getName())
                .filter(id -> queue.joinable(id) && settings.followGroups().contains(groupOf(id)))
                .ifPresent(id -> pull(party.get(), id, "party.following"));
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        parties.leave(player.getUniqueId())
                .ifPresent(d -> announceDeparture(d, player, "party.member-disconnected"));
    }

    private void expireInvites() {
        for (Parties.Expired e : parties.expire(System.currentTimeMillis())) {
            String invitee = nameOf(e.invitee());
            proxy.getPlayer(e.invitee()).ifPresent(p -> lang.send(p, "party.invite-expired-you",
                    Placeholder.unparsed("leader", nameOf(e.party().leader()))));
            proxy.getPlayer(e.party().leader()).ifPresent(l -> lang.send(l,
                    e.disbanded() ? "party.invite-expired-disbanded" : "party.invite-expired",
                    Placeholder.unparsed("player", invitee)));
        }
    }

    // ------------------------------------------------------------------ helpers

    private void pull(PartyView party, String serverId, String messageKey) {
        for (UUID m : party.others(party.leader())) {
            proxy.getPlayer(m).filter(p -> !onServer(p, serverId)).ifPresent(p -> {
                lang.send(p, messageKey, Placeholder.unparsed("target", queue.displayName(QueueTarget.server(serverId))));
                queue.join(p, QueueTarget.server(serverId), QueueSettings.FOLLOW_PRIORITY, false);
            });
        }
    }

    private void announceDeparture(Departure d, Player who, String key) {
        if (d.disbanded()) {
            broadcast(d.before(), who.getUniqueId(), "party.disbanded-empty");
            return;
        }
        PartyView after = d.after().orElseThrow();
        broadcast(after, null, key, name(who));
        if (d.leaderChanged()) {
            broadcast(after, null, "party.new-leader", Placeholder.unparsed("player", nameOf(after.leader())));
        }
    }

    /** Sends a message to every member except {@code skip} (may be null). */
    private void broadcast(PartyView party, UUID skip, String key, TagResolver... resolvers) {
        Component msg = lang.get(key, resolvers);
        for (UUID m : party.members()) {
            if (!m.equals(skip)) {
                proxy.getPlayer(m).ifPresent(p -> p.sendMessage(msg));
            }
        }
    }

    private void fail(Player player, Failure failure, String other) {
        lang.send(player, "party.fail." + failure.name().toLowerCase(java.util.Locale.ROOT),
                Placeholder.unparsed("player", other));
    }

    /** The only party that invited {@code player}, if exactly one did. */
    private Optional<UUID> onlyInvite(Player player) {
        List<UUID> leaders = parties.invitesFor(player.getUniqueId(), System.currentTimeMillis());
        return leaders.size() == 1 ? Optional.of(leaders.getFirst()) : Optional.empty();
    }

    private int maxSize(Player leader) {
        return settings.size().get(ranks.rank(leader));
    }

    private String groupOf(String serverId) {
        return queue.groupOf(serverId).orElse("");
    }

    private static boolean onServer(Player p, String serverId) {
        return p.getCurrentServer().map(c -> c.getServerInfo().getName().equals(serverId)).orElse(false);
    }

    private String nameOf(UUID player) {
        return proxy.getPlayer(player).map(Player::getUsername).orElse("?");
    }

    private static TagResolver name(Player p) {
        return Placeholder.unparsed("player", p.getUsername());
    }

    private static TagResolver name(String tag, Player p) {
        return Placeholder.unparsed(tag, p.getUsername());
    }
}
