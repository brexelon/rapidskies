package us.rapidskies.proxy.friend;

import us.rapidskies.common.config.ConfigView;
import us.rapidskies.common.rank.RankValues;

/** {@code friends:} in rapid-proxy's config.yml: list size by rank (PLAN.md §6.1). */
public record FriendSettings(RankValues limit) {

    public static FriendSettings from(ConfigView c) {
        return new FriendSettings(RankValues.of(100, 150, 250, 500).overriddenBy(c.section("friends.limit")));
    }
}
