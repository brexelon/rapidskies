package us.rapidskies.proxy.grant;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import us.rapidskies.common.grant.Grant;
import us.rapidskies.common.rank.Rank;
import us.rapidskies.common.rank.StaffRole;

/**
 * What {@code /grant} gives: a paid rank or team role (a LuckPerms group), or one
 * permission node. Also decides who may grant it ({@link #denial}).
 */
public record GrantTarget(Grant.Kind kind, String value) {

    /** Use {@code /grant} and {@code /grants} at all (and see grant history). */
    public static final String USE = "rapid.command.grant";
    /** Grant Rapid+, Rapid++ and Rapid+++. */
    public static final String GRANT_RANK = "rapid.grant.rank";
    /** Grant team roles below your own. */
    public static final String GRANT_STAFF = "rapid.grant.staff";
    /** Grant permission nodes you hold yourself. */
    public static final String GRANT_PERMISSION = "rapid.grant.permission";
    /** Revoke grants (only ones you would be allowed to give). */
    public static final String REVOKE = "rapid.grant.revoke";

    // Lowercase dotted node; a segment may be "*". LuckPerms reads keys starting with these
    // prefixes as groups, prefixes and meta, not permissions, so they can't be granted as
    // one (group.owner as a "permission" would skip the team-role check).
    private static final Pattern NODE = Pattern.compile("(?:[a-z0-9_\\-]+|\\*)(?:\\.(?:[a-z0-9_\\-]+|\\*))*");
    private static final List<String> RESERVED_PREFIXES =
            List.of("group.", "prefix.", "suffix.", "meta.", "weight.", "displayname.");

    /**
     * A rank or team role by its group name. Pluses also work: {@code rapid++} is
     * {@code rapidplusplus}.
     */
    public static Optional<GrantTarget> rank(String input) {
        String key = input.toLowerCase(Locale.ROOT).replace("+", "plus");
        for (Rank r : Rank.values()) {
            if (r != Rank.DEFAULT && r.group().equals(key)) {
                return Optional.of(new GrantTarget(Grant.Kind.RANK, r.group()));
            }
        }
        for (StaffRole s : StaffRole.values()) {
            if (s.group().equals(key)) {
                return Optional.of(new GrantTarget(Grant.Kind.RANK, s.group()));
            }
        }
        return Optional.empty();
    }

    public static Optional<GrantTarget> permission(String input) {
        String node = input.toLowerCase(Locale.ROOT);
        if (node.length() > 255 || !NODE.matcher(node).matches()
                || RESERVED_PREFIXES.stream().anyMatch(node::startsWith)) {
            return Optional.empty();
        }
        return Optional.of(new GrantTarget(Grant.Kind.PERMISSION, node));
    }

    /** Every rank and team role name, for tab completion and the usage message. */
    public static List<String> rankNames() {
        List<String> names = new ArrayList<>();
        Arrays.stream(Rank.values()).filter(r -> r != Rank.DEFAULT).forEach(r -> names.add(r.group()));
        Arrays.stream(StaffRole.values()).forEach(s -> names.add(s.group()));
        return names;
    }

    /** The team role this grants, if it's one. */
    public Optional<StaffRole> staffRole() {
        if (kind != Grant.Kind.RANK) {
            return Optional.empty();
        }
        return Arrays.stream(StaffRole.values()).filter(s -> s.group().equals(value)).findFirst();
    }

    /**
     * Why the grantor may not give or revoke this, as a lang key; empty if they may.
     *
     * @param console the console may do anything (the store delivers through it)
     * @param has     the grantor's permission check
     * @param role    the grantor's highest team role
     */
    public Optional<String> denial(boolean console, Predicate<String> has, Optional<StaffRole> role) {
        if (console) {
            return Optional.empty();
        }
        if (kind == Grant.Kind.PERMISSION) {
            if (!has.test(GRANT_PERMISSION)) {
                return Optional.of("grant.denied.permission");
            }
            // No handing out more than you have.
            return has.test(value) ? Optional.empty() : Optional.of("grant.denied.node");
        }
        Optional<StaffRole> granted = staffRole();
        if (granted.isEmpty()) {
            return has.test(GRANT_RANK) ? Optional.empty() : Optional.of("grant.denied.rank");
        }
        if (!has.test(GRANT_STAFF)) {
            return Optional.of("grant.denied.staff");
        }
        // StaffRole is ordered highest first: only roles strictly below your own.
        boolean above = role.isPresent() && role.get().ordinal() < granted.get().ordinal();
        return above ? Optional.empty() : Optional.of("grant.denied.staff-level");
    }
}
