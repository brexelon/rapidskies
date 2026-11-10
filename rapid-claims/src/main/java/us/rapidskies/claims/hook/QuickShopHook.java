package us.rapidskies.claims.hook;

import com.ghostchu.quickshop.api.event.management.ShopCreateEvent;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import us.rapidskies.claims.ClaimService;
import us.rapidskies.claims.RapidClaims;
import us.rapidskies.claims.model.Role;

/**
 * Chest shops only inside claims where the creator is at least a Member (PLAN.md §4: the
 * QuickShop-Hikari protection hook). Wilderness shops are refused too: a shop in the open
 * could be broken into by anyone. QuickShop's own protection check (a fake block break) is
 * switched off in its config, since Builder access isn't what a shopkeeper needs.
 *
 * <p>Only loaded when QuickShop-Hikari is installed.
 */
public final class QuickShopHook implements Listener {

    private final RapidClaims plugin;

    public QuickShopHook(RapidClaims plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onCreate(ShopCreateEvent event) {
        if (!event.phase().cancellable() || event.isCancelled()) {
            return;
        }
        Location at = event.location();
        UUID creator = event.user().getUniqueIdOptional().orElse(null);
        if (at == null || at.getWorld() == null || creator == null) {
            return;
        }
        ClaimService service = plugin.service();
        if (service.bypassing(creator)) {
            return;
        }
        if (!service.claimable(at.getWorld()) || !service.isClaimed(at)) {
            event.setCancelled(true, plugin.lang().get("shop.claims-only"));
            return;
        }
        if (!plugin.land().role(creator, ClaimService.chunk(at)).atLeast(Role.MEMBER)) {
            event.setCancelled(true, plugin.lang().get("shop.needs-member"));
        }
    }
}
