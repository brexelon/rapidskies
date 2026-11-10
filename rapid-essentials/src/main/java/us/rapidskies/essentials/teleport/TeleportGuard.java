package us.rapidskies.essentials.teleport;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/**
 * A reason a player can't use a teleport command right now, registered by another plugin
 * with {@link Teleports#addGuard}. rapid-horizons refuses while combat-tagged, so
 * {@code /spawn}, {@code /home}, {@code /warp}, {@code /back} and {@code /tpa} are no escape
 * from a fight.
 *
 * <p>Checked on the main thread when the command is used and again when a warmup ends.
 */
@FunctionalInterface
public interface TeleportGuard {

    /** The message to show the player if they can't teleport now, or null if they can. */
    Component check(Player player);
}
