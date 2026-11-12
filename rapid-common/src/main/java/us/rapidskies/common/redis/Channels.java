package us.rapidskies.common.redis;

import java.util.UUID;

/** Pub/sub channel names and their JSON payloads (all under {@code rapid:}). */
public final class Channels {

    /** A player's profile row changed (plus color, ...): servers holding it reload it. */
    public static final String PROFILE = "profile";
    /** A Bolts balance changed: the server the player is on tells them. */
    public static final String BOLTS = "bolts";
    /** Maintenance mode was switched on or off. */
    public static final String MAINTENANCE = "maintenance";

    /**
     * A backend asks the proxy to send a player somewhere (hub selector, NPCs). The proxy
     * queues them like {@code /play} would.
     */
    public static final String CONNECT = "connect";

    /**
     * A backend is shutting down (restart, stop) and is about to move everyone off. Sent
     * before players are kicked, so the proxy can put them back in line for this server.
     */
    public static final String SERVER_STOPPING = "server-stopping";

    /**
     * A staff member vanished or reappeared. The proxy hides vanished staff from friend
     * notices, friend lists and {@code /msg}; backends hide them from players.
     */
    public static final String VANISH = "vanish";

    /**
     * A staff member froze or released a player. The proxy blocks a frozen player's proxy
     * commands ({@code /play}, {@code /server}...) so they can't leave, and tells staff if
     * they disconnect.
     */
    public static final String FREEZE = "freeze";

    /**
     * A Coins or Crowns balance changed on one server (Vault, auction house): the server
     * the player is on refreshes its cached balance.
     */
    public static final String MODE_BALANCE = "mode-balance";

    /**
     * Claims, trust or towns changed on one Horizons server (rapid-claims): other instances
     * reload what changed from the database. Unused while Horizons runs on one server.
     */
    public static final String CLAIMS = "claims";

    /**
     * An admin's {@code /broadcast} (rapid-essentials): every backend shows it to everyone
     * on it, including the server it came from.
     */
    public static final String BROADCAST = "broadcast";

    /**
     * The auction house (rapid-market) left a notice for a player (an item sold, a listing
     * expired or was taken down). The server the player is on shows it and deletes it; if
     * nobody has them, it waits in {@code market_notices} until their next login.
     */
    public static final String MARKET = "market";

    /**
     * Something staff should look at (rapid-market: repeated far-below-average sales
     * between two players). The proxy shows it in staff chat on every server.
     */
    public static final String STAFF_ALERT = "staff-alert";

    /**
     * Cosmetics were granted to a player on another server (a SkyRealms season award, a
     * level-up, an admin): the server they're on reloads their wardrobe and names what's new.
     */
    public static final String COSMETICS = "cosmetics";

    /**
     * A rank pass was bought with Bolts (rapid-core's Bolt shop): the proxy grants the
     * pending {@code shop_purchases} rows through {@code /grant}. It also checks for them
     * every minute, so a lost message only delays the grant.
     */
    public static final String RANK_PASS = "rank-pass";

    /**
     * A player became AFK or came back on a backend (rapid-core's AFK tracker). The proxy
     * moves players who stay AFK long enough to the Limbo server.
     */
    public static final String AFK = "afk";

    /** Redis key holding "true" while maintenance mode is on. */
    public static final String MAINTENANCE_KEY = "maintenance";

    /** Redis set of the UUIDs of vanished staff. Persists across sessions until they reappear. */
    public static final String VANISHED_KEY = "vanished";

    /** Redis set of the UUIDs of frozen players. Persists across relogs until released. */
    public static final String FROZEN_KEY = "frozen";

    /**
     * Redis set, written by the proxy, of online players who share an IP address with an
     * account that connected before them. They don't earn activity Bolts (PLAN.md §6.2).
     */
    public static final String SHARED_IP_KEY = "shared-ip";

    private Channels() {
    }

    public record ProfileChanged(String uuid, String origin) {
        public static ProfileChanged of(UUID uuid, String origin) {
            return new ProfileChanged(uuid.toString(), origin);
        }

        public UUID player() {
            return UUID.fromString(uuid);
        }
    }

    public record BoltsChanged(String uuid, long delta, long balance, String reason) {
        public UUID player() {
            return UUID.fromString(uuid);
        }
    }

    public record MaintenanceChanged(boolean enabled, String by) {
    }

    public record VanishChanged(String uuid, boolean vanished) {
        public UUID player() {
            return UUID.fromString(uuid);
        }
    }

    /** {@code by} is the staff member's name, for the proxy's alerts. */
    public record FreezeChanged(String uuid, boolean frozen, String by) {
        public UUID player() {
            return UUID.fromString(uuid);
        }
    }

    /** {@code mode} is an {@link us.rapidskies.common.economy.EconomyMode} id. */
    public record ModeBalanceChanged(String mode, String uuid, long balance, String origin) {
        public UUID player() {
            return UUID.fromString(uuid);
        }
    }

    /**
     * What to reload after a claims change. {@code kind} is {@code chunk} (key
     * {@code world;x;z}), {@code owner} (key {@code PLAYER:<uuid>} or {@code TOWN:<uuid>}:
     * its trust, denials and allowance) or {@code town} (key: the town's uuid; its members,
     * plots, projects and state).
     */
    public record ClaimsChanged(String kind, String key, String origin) {
    }

    /** {@code noticeId} is the {@code market_notices} row; {@code mode} an EconomyMode id. */
    public record MarketNotice(String uuid, long noticeId, String mode, String origin) {
        public UUID player() {
            return UUID.fromString(uuid);
        }
    }

    /** {@code message} is MiniMessage (brand tags allowed); {@code server} where it happened. */
    public record StaffAlert(String server, String message) {
    }

    /** {@code message} is MiniMessage (brand tags allowed); {@code by} the sender's name, for the log. */
    public record Broadcast(String message, String by) {
    }

    /**
     * {@code startedAt} is the stopping instance's, from its heartbeat: anything up to it is
     * this instance, anything later is the server back up. {@code players} are the UUIDs of
     * everyone on it.
     */
    public record ServerStopping(String server, String group, long startedAt, java.util.List<String> players) {
    }

    /** {@code unlocked} are the cosmetic ids that are new to the player. */
    public record CosmeticsChanged(String uuid, java.util.List<String> unlocked, String origin) {
        public UUID player() {
            return UUID.fromString(uuid);
        }
    }

    /** {@code purchaseId} is the {@code shop_purchases} row to deliver. */
    public record RankPassBought(long purchaseId, String uuid) {
    }

    /**
     * {@code server} is where it happened, so the proxy can drop a late message from a server
     * the player has already left. {@code idleSince} is when they last gave input (epoch
     * millis); only meaningful when {@code afk} is true.
     */
    public record AfkChanged(String uuid, String server, boolean afk, long idleSince) {
        public UUID player() {
            return UUID.fromString(uuid);
        }
    }

    /** {@code target} is a server group ({@code smp}) or a server id ({@code smp-1}). */
    public record ConnectRequest(String uuid, String target) {
        public UUID player() {
            return UUID.fromString(uuid);
        }
    }
}
