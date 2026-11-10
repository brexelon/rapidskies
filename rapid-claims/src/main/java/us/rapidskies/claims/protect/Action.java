package us.rapidskies.claims.protect;

import java.util.Locale;
import us.rapidskies.claims.model.Role;

/** Something a player does on claimed land, and the role it takes (PLAN.md §4.1, trust roles). */
public enum Action {
    /** Doors, trapdoors, gates, buttons, levers, pressure plates, crafting stations, vehicles. */
    USE(Role.TRUSTED),
    /** Chests, barrels, furnaces, hoppers, jukeboxes, lecterns' books, flower pots, cauldrons. */
    CONTAINER(Role.MEMBER),
    /** Harvesting and planting crops, bone meal, beds, cake, beehives, berries. */
    FARM(Role.MEMBER),
    /** Hurting, breeding, leashing, shearing, riding or trading with animals and villagers. */
    ANIMALS(Role.MEMBER),
    /** Breaking and placing blocks, buckets, item frames, armor stands, signs, redstone settings, fire. */
    BUILD(Role.BUILDER),
    /** Trust, denials and flags. */
    MANAGE(Role.CO_OWNER);

    public final Role required;

    Action(Role required) {
        this.required = required;
    }

    public boolean allowedFor(Role role) {
        return role.atLeast(required);
    }

    /** Lang key part ({@code container}). */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
