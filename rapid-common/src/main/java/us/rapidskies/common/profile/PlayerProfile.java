package us.rapidskies.common.profile;

import java.time.Instant;
import java.util.UUID;
import us.rapidskies.common.rank.PlusColor;

/** A row of {@code players} (PLAN.md §10). Immutable; reload it to see changes. */
public record PlayerProfile(
        UUID uuid,
        String name,
        Instant firstJoin,
        Instant lastJoin,
        String lastServer,
        long networkXp,
        String locale,
        /* The stored choice, or null. Use {@link PlusColor#effective} for display. */
        PlusColor plusColor,
        long tailwindBankedSeconds) {

    public boolean isNew() {
        return firstJoin.equals(lastJoin);
    }
}
