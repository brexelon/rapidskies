package us.rapidskies.proxy.party;

import java.util.Set;
import us.rapidskies.common.config.ConfigView;
import us.rapidskies.common.rank.RankValues;

/**
 * {@code parties:} in rapid-proxy's config.yml. Defaults here match the bundled file, so a
 * config written by an older build (without this section) behaves the same.
 *
 * @param size          max members by the leader's rank, leader included (PLAN.md §6.1)
 * @param followGroups  server groups members follow their leader into
 */
public record PartySettings(RankValues size, long inviteMillis, Set<String> followGroups) {

    public static PartySettings from(ConfigView c) {
        RankValues size = RankValues.of(4, 6, 8, 12).overriddenBy(c.section("parties.size"));
        long invite = Math.max(10, c.integer("parties.invite-seconds", 60)) * 1000L;
        Set<String> follow = c.contains("parties.follow-groups")
                ? Set.copyOf(c.stringList("parties.follow-groups"))
                : Set.of("hub", "skyrealms", "smp");
        return new PartySettings(size, invite, follow);
    }
}
