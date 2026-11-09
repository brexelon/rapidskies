package us.rapidskies.proxy.queue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import us.rapidskies.common.config.ConfigView;
import us.rapidskies.common.rank.RankValues;

/**
 * {@code queue:} in rapid-proxy's config.yml.
 *
 * @param groups         server groups players can join with {@code /play}, and their display
 *                       names. Staff-only servers (build, staging) are left out on purpose.
 * @param priority       place in line by rank; higher goes first (PLAN.md §6.1)
 * @param staffPriority  for anyone with a team role
 */
public record QueueSettings(Map<String, String> groups, RankValues priority, int staffPriority) {

    /** Party members following their leader go ahead of everyone. */
    public static final int FOLLOW_PRIORITY = Integer.MAX_VALUE;

    public static QueueSettings from(ConfigView c) {
        Map<String, String> groups = new LinkedHashMap<>();
        ConfigView section = c.section("queue.groups");
        for (String key : section.keys()) {
            groups.put(key, section.string(key, key));
        }
        if (groups.isEmpty()) {
            groups.put("hub", "Lobby");
            groups.put("skyrealms", "Skyrealms");
            groups.put("smp", "Horizons SMP");
        }
        return new QueueSettings(Collections.unmodifiableMap(groups), // keeps file order for /play
                RankValues.of(0, 1, 1, 2).overriddenBy(c.section("queue.priority")),
                c.integer("queue.priority.staff", 3));
    }
}
