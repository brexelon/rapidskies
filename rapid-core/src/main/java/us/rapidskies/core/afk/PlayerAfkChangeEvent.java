package us.rapidskies.core.afk;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/** A player became AFK or came back ({@link AfkTracker}). Fired on the main thread. */
public final class PlayerAfkChangeEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final boolean afk;

    public PlayerAfkChangeEvent(Player player, boolean afk) {
        super(player);
        this.afk = afk;
    }

    /** True if the player is now AFK, false if they just came back. */
    public boolean isAfk() {
        return afk;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
