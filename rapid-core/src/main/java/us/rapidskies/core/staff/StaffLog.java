package us.rapidskies.core.staff;

import java.util.UUID;
import org.bukkit.entity.Player;
import us.rapidskies.common.db.Database;
import us.rapidskies.common.staff.StaffActions;
import us.rapidskies.core.RapidCore;

/** Records staff tool use in {@code staff_actions} (PLAN.md §9), off the main thread. */
public final class StaffLog {

    private final RapidCore plugin;
    private final Database db;
    private final StaffActions actions;

    public StaffLog(RapidCore plugin, Database db) {
        this.plugin = plugin;
        this.db = db;
        this.actions = new StaffActions(db);
    }

    /** {@code target} and {@code detail} may be null. */
    public void log(Player staff, String action, Player target, String detail) {
        UUID staffId = staff.getUniqueId();
        UUID targetId = target == null ? null : target.getUniqueId();
        String server = plugin.settings().serverId();
        db.async(() -> {
            actions.log(staffId, action, targetId, server, detail);
            return null;
        }).exceptionally(e -> {
            plugin.getSLF4JLogger().warn("Couldn't log staff action {} by {}: {}", action, staff.getName(),
                    e.getMessage());
            return null;
        });
    }
}
