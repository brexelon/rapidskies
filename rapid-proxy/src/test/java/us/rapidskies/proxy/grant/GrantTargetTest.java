package us.rapidskies.proxy.grant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import us.rapidskies.common.grant.Grant;
import us.rapidskies.common.rank.StaffRole;

class GrantTargetTest {

    @Test
    void ranksByGroupNameOrPluses() {
        assertEquals(Optional.of(new GrantTarget(Grant.Kind.RANK, "rapidplusplus")), GrantTarget.rank("RapidPlusPlus"));
        assertEquals(Optional.of(new GrantTarget(Grant.Kind.RANK, "rapidplusplusplus")), GrantTarget.rank("rapid+++"));
        assertEquals(Optional.of(new GrantTarget(Grant.Kind.RANK, "moderator")), GrantTarget.rank("moderator"));
        assertTrue(GrantTarget.rank("default").isEmpty(), "everyone has default already");
        assertTrue(GrantTarget.rank("vip").isEmpty());
    }

    @Test
    void permissionNodesAreValidatedAndLowercased() {
        assertEquals(Optional.of(new GrantTarget(Grant.Kind.PERMISSION, "worldedit.wand")),
                GrantTarget.permission("WorldEdit.Wand"));
        assertTrue(GrantTarget.permission("fawe.*").isPresent());
        assertTrue(GrantTarget.permission("*").isPresent());
        for (String bad : new String[] {"", "a..b", ".a", "a.", "has space", "a=b", "group.owner", "prefix.100.x",
                "meta.key.value", "weight.10", "displayname.x"}) {
            assertTrue(GrantTarget.permission(bad).isEmpty(), bad);
        }
    }

    @Test
    void consoleMayGrantAnything() {
        assertEquals(Optional.empty(), rank("owner").denial(true, p -> false, Optional.empty()));
    }

    @Test
    void paidRanksNeedTheRankPermission() {
        GrantTarget plus = rank("rapidplus");
        assertEquals(Optional.of("grant.denied.rank"), plus.denial(false, has(), Optional.empty()));
        assertEquals(Optional.empty(), plus.denial(false, has(GrantTarget.GRANT_RANK), Optional.empty()));
    }

    @Test
    void teamRolesOnlyStrictlyBelowYourOwn() {
        Predicate<String> staff = has(GrantTarget.GRANT_STAFF);
        assertEquals(Optional.of("grant.denied.staff"), rank("helper").denial(false, has(), Optional.of(StaffRole.OWNER)));
        assertEquals(Optional.empty(), rank("moderator").denial(false, staff, Optional.of(StaffRole.ADMIN)));
        assertEquals(Optional.empty(), rank("developer").denial(false, staff, Optional.of(StaffRole.ADMIN)));
        assertEquals(Optional.of("grant.denied.staff-level"),
                rank("admin").denial(false, staff, Optional.of(StaffRole.ADMIN)), "not your own level");
        assertEquals(Optional.of("grant.denied.staff-level"),
                rank("owner").denial(false, staff, Optional.of(StaffRole.ADMIN)));
        assertEquals(Optional.of("grant.denied.staff-level"),
                rank("helper").denial(false, staff, Optional.empty()), "a permission alone isn't a role");
    }

    @Test
    void permissionsOnlyIfYouHoldThem() {
        GrantTarget wand = GrantTarget.permission("worldedit.wand").orElseThrow();
        assertEquals(Optional.of("grant.denied.permission"), wand.denial(false, has("worldedit.wand"), Optional.empty()));
        assertEquals(Optional.of("grant.denied.node"),
                wand.denial(false, has(GrantTarget.GRANT_PERMISSION), Optional.empty()));
        assertEquals(Optional.empty(),
                wand.denial(false, has(GrantTarget.GRANT_PERMISSION, "worldedit.wand"), Optional.empty()));
    }

    @Test
    void revokeIdsMayHaveAHash() {
        assertEquals(12, GrantsCommand.parseId("12"));
        assertEquals(12, GrantsCommand.parseId("#12"));
        assertEquals(-1, GrantsCommand.parseId("twelve"));
    }

    private static GrantTarget rank(String name) {
        return GrantTarget.rank(name).orElseThrow();
    }

    private static Predicate<String> has(String... permissions) {
        return Set.of(permissions)::contains;
    }
}
