package us.rapidskies.proxy.party;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Every party on the network (PLAN.md §7). Pure state with no Velocity types, so it's
 * unit-tested directly; {@link PartyCommand} does the messaging.
 *
 * <p>Parties live in this proxy's memory: there is one proxy, and a proxy restart drops
 * every player anyway. A second proxy would move this state to Redis.
 *
 * <p>A party exists from the first invite. It needs two members to survive: when an
 * invite expires or someone leaves and only the leader is left with no invites out, it's
 * disbanded. All methods are synchronized; {@link PartyView}s are immutable snapshots.
 */
public final class Parties {

    private final Map<UUID, Party> byMember = new HashMap<>();

    private static final class Party {
        final UUID id = UUID.randomUUID();
        UUID leader;
        final LinkedHashSet<UUID> members = new LinkedHashSet<>();
        /** Invitee -> expiry (epoch millis). */
        final Map<UUID, Long> invites = new LinkedHashMap<>();

        Party(UUID leader) {
            this.leader = leader;
            members.add(leader);
        }

        PartyView view() {
            return new PartyView(id, leader, List.copyOf(members));
        }

        boolean lonely() {
            return members.size() < 2 && invites.isEmpty();
        }
    }

    /** A snapshot. {@code members} is in join order and includes the leader. */
    public record PartyView(UUID id, UUID leader, List<UUID> members) {
        public boolean isLeader(UUID player) {
            return leader.equals(player);
        }

        /** Everyone except {@code player}. */
        public List<UUID> others(UUID player) {
            return members.stream().filter(m -> !m.equals(player)).toList();
        }
    }

    public enum Failure {
        SELF, NOT_IN_PARTY, NOT_LEADER, ALREADY_IN_PARTY, TARGET_IN_PARTY, ALREADY_INVITED, NO_INVITE, FULL, NOT_MEMBER
    }

    /** Either a value or the reason nothing changed. */
    public record Result<T>(T value, Failure failure) {
        static <T> Result<T> ok(T value) {
            return new Result<>(value, null);
        }

        static <T> Result<T> fail(Failure failure) {
            return new Result<>(null, failure);
        }

        public boolean ok() {
            return failure == null;
        }
    }

    /** After someone leaves or is kicked: what's left, and whether the leader changed. */
    public record Departure(PartyView before, Optional<PartyView> after, boolean leaderChanged) {
        public boolean disbanded() {
            return after.isEmpty();
        }
    }

    /** An invite that ran out, for telling both sides. */
    public record Expired(PartyView party, UUID invitee, boolean disbanded) {
    }

    public synchronized Optional<PartyView> of(UUID player) {
        Party p = byMember.get(player);
        return p == null ? Optional.empty() : Optional.of(p.view());
    }

    /**
     * The leader (or someone not yet in a party, who becomes the leader of a new one)
     * invites {@code target}. {@code maxSize} counts members plus open invites.
     */
    public synchronized Result<PartyView> invite(UUID inviter, UUID target, int maxSize, long now, long ttlMillis) {
        if (inviter.equals(target)) {
            return Result.fail(Failure.SELF);
        }
        Party party = byMember.get(inviter);
        if (party != null && !party.leader.equals(inviter)) {
            return Result.fail(Failure.NOT_LEADER);
        }
        if (byMember.containsKey(target)) {
            return Result.fail(Failure.TARGET_IN_PARTY);
        }
        if (party != null) {
            dropExpired(party, now);
            if (party.invites.containsKey(target)) {
                return Result.fail(Failure.ALREADY_INVITED);
            }
            if (party.members.size() + party.invites.size() >= maxSize) {
                return Result.fail(Failure.FULL);
            }
        } else {
            if (maxSize < 2) {
                return Result.fail(Failure.FULL);
            }
            party = new Party(inviter);
            byMember.put(inviter, party);
        }
        party.invites.put(target, now + ttlMillis);
        return Result.ok(party.view());
    }

    /** {@code target} accepts an invite from the party {@code member} is in. */
    public synchronized Result<PartyView> accept(UUID target, UUID member, int maxSize, long now) {
        if (byMember.containsKey(target)) {
            return Result.fail(Failure.ALREADY_IN_PARTY);
        }
        Party party = byMember.get(member);
        if (party == null) {
            return Result.fail(Failure.NO_INVITE);
        }
        dropExpired(party, now);
        if (party.invites.remove(target) == null) {
            disbandIfLonely(party);
            return Result.fail(Failure.NO_INVITE);
        }
        if (party.members.size() >= maxSize) {
            disbandIfLonely(party);
            return Result.fail(Failure.FULL);
        }
        party.members.add(target);
        byMember.put(target, party);
        return Result.ok(party.view());
    }

    /** Returns the party whose invite was declined, if there was one. */
    public synchronized Optional<PartyView> deny(UUID target, UUID member) {
        Party party = byMember.get(member);
        if (party == null || party.invites.remove(target) == null) {
            return Optional.empty();
        }
        PartyView view = party.view();
        disbandIfLonely(party);
        return Optional.of(view);
    }

    /** {@code player} leaves (or disconnected). If they led, the longest-standing member takes over. */
    public synchronized Optional<Departure> leave(UUID player) {
        Party party = byMember.get(player);
        return party == null ? Optional.empty() : Optional.of(remove(party, player));
    }

    public synchronized Result<Departure> kick(UUID leader, UUID target) {
        Failure f = leaderAction(leader, target);
        return f != null ? Result.fail(f) : Result.ok(remove(byMember.get(leader), target));
    }

    /** Makes {@code target} the leader. */
    public synchronized Result<PartyView> transfer(UUID leader, UUID target) {
        Failure f = leaderAction(leader, target);
        if (f != null) {
            return Result.fail(f);
        }
        Party party = byMember.get(leader);
        party.leader = target;
        return Result.ok(party.view());
    }

    /** The leader ends the party. Returns it as it was. */
    public synchronized Result<PartyView> disband(UUID leader) {
        Party party = byMember.get(leader);
        if (party == null) {
            return Result.fail(Failure.NOT_IN_PARTY);
        }
        if (!party.leader.equals(leader)) {
            return Result.fail(Failure.NOT_LEADER);
        }
        PartyView view = party.view();
        party.members.forEach(byMember::remove);
        return Result.ok(view);
    }

    /** Drops invites past their expiry, and parties left with nobody but the leader. */
    public synchronized List<Expired> expire(long now) {
        List<Expired> out = new ArrayList<>();
        for (Party party : new LinkedHashSet<>(byMember.values())) {
            List<UUID> gone = dropExpired(party, now);
            if (gone.isEmpty()) {
                continue;
            }
            PartyView view = party.view();
            boolean disbanded = disbandIfLonely(party);
            gone.forEach(invitee -> out.add(new Expired(view, invitee, disbanded)));
        }
        return out;
    }

    /** Leaders of the parties that have an open invite for {@code target}. */
    public synchronized List<UUID> invitesFor(UUID target, long now) {
        List<UUID> out = new ArrayList<>();
        for (Party party : new LinkedHashSet<>(byMember.values())) {
            Long expiry = party.invites.get(target);
            if (expiry != null && expiry > now) {
                out.add(party.leader);
            }
        }
        return out;
    }

    /** Why {@code leader} can't act on member {@code target}, or null if they can. */
    private Failure leaderAction(UUID leader, UUID target) {
        Party party = byMember.get(leader);
        if (party == null) {
            return Failure.NOT_IN_PARTY;
        }
        if (!party.leader.equals(leader)) {
            return Failure.NOT_LEADER;
        }
        if (leader.equals(target)) {
            return Failure.SELF;
        }
        if (!party.members.contains(target)) {
            return Failure.NOT_MEMBER;
        }
        return null;
    }

    private Departure remove(Party party, UUID player) {
        PartyView before = party.view();
        party.members.remove(player);
        byMember.remove(player);
        boolean leaderChanged = false;
        if (party.leader.equals(player) && !party.members.isEmpty()) {
            party.leader = party.members.getFirst();
            leaderChanged = true;
        }
        if (party.members.size() < 2) {
            // A party of one isn't a party, even with invites out: the invites are pointless
            // once everyone they were meant to join has gone.
            party.members.forEach(byMember::remove);
            party.invites.clear();
            return new Departure(before, Optional.empty(), leaderChanged);
        }
        return new Departure(before, Optional.of(party.view()), leaderChanged);
    }

    private static List<UUID> dropExpired(Party party, long now) {
        List<UUID> gone = new ArrayList<>();
        for (Iterator<Map.Entry<UUID, Long>> it = party.invites.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Long> e = it.next();
            if (e.getValue() <= now) {
                gone.add(e.getKey());
                it.remove();
            }
        }
        return gone;
    }

    private boolean disbandIfLonely(Party party) {
        if (!party.lonely()) {
            return false;
        }
        party.members.forEach(byMember::remove);
        return true;
    }
}
