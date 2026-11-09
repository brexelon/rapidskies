package us.rapidskies.common.grant;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * One row of {@code grants}: a rank or permission given to a player with {@code /grant}
 * (or by the store), and whether it was revoked.
 *
 * @param grantedBy  null when the console granted it (the store, scripts)
 * @param startsAt   when its time begins: later than {@code createdAt} when it was stacked
 *                   behind time the player already had (see {@link GrantTerms})
 * @param expiresAt  null for a permanent grant
 * @param reason     may be null
 * @param txn        the store transaction id, if any (makes store deliveries idempotent)
 * @param revokedAt  null unless revoked; the revoke fields are all null together
 */
public record Grant(
        long id,
        UUID player,
        Kind kind,
        String value,
        UUID grantedBy,
        String grantedByName,
        String reason,
        String txn,
        Instant createdAt,
        Instant startsAt,
        Instant expiresAt,
        Instant revokedAt,
        UUID revokedBy,
        String revokedByName,
        String revokeReason) {

    /** What a grant gives. */
    public enum Kind {
        /** A LuckPerms group: a paid rank or a team role. */
        RANK,
        /** A single permission node, true in every context. */
        PERMISSION;

        /** Stored in {@code grants.kind}. */
        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Kind byId(String id) {
            return valueOf(id.toUpperCase(Locale.ROOT));
        }
    }

    public enum Status {
        /** Its time is running now. */
        ACTIVE,
        /** Stacked behind other time; it starts later. */
        QUEUED,
        EXPIRED,
        REVOKED
    }

    public boolean permanent() {
        return expiresAt == null;
    }

    public boolean revoked() {
        return revokedAt != null;
    }

    /** Not revoked and not expired (queued grants count: their time is still owed). */
    public boolean live(Instant now) {
        return !revoked() && (permanent() || expiresAt.isAfter(now));
    }

    public Status status(Instant now) {
        if (revoked()) {
            return Status.REVOKED;
        }
        if (!live(now)) {
            return Status.EXPIRED;
        }
        return startsAt.isAfter(now) ? Status.QUEUED : Status.ACTIVE;
    }

    /** How long it lasts in total; null if permanent. */
    public Duration length() {
        return permanent() ? null : Duration.between(startsAt, expiresAt);
    }

    /** The LuckPerms node key this grant controls ({@code group.<name>} or the permission). */
    public String nodeKey() {
        return nodeKey(kind, value);
    }

    public static String nodeKey(Kind kind, String value) {
        return kind == Kind.RANK ? "group." + value : value;
    }
}
