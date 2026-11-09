package us.rapidskies.hub;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.core.RapidCore;
import us.rapidskies.core.gui.Icons;
import us.rapidskies.core.gui.Menu;
import us.rapidskies.hub.HubSettings.Mode;

/**
 * The game menu (hotbar compass, {@code /games}) and joining a gamemode, which the NPCs use
 * too. Joining hands the player to the proxy's queue ({@link RapidCore#connect}), exactly as
 * if they'd typed {@code /play <group>}.
 */
public final class ModeSelector {

    private static final long JOIN_COOLDOWN_MILLIS = 1500;

    private final RapidHub hub;
    private final Map<UUID, Long> lastJoin = new HashMap<>();

    public ModeSelector(RapidHub hub) {
        this.hub = hub;
    }

    /** A mode's display name (lang {@code modes.<id>.name}). */
    public Component name(Mode mode) {
        return hub.lang().get("modes." + mode.id() + ".name");
    }

    /** "12 playing", or that the mode isn't open right now. */
    public Component status(Mode mode) {
        ModeCounts.Status s = hub.counts().status(mode.group());
        return s.open()
                ? hub.lang().get("selector.players", Placeholder.unparsed("count", Integer.toString(s.players())))
                : hub.lang().get("selector.closed");
    }

    public void open(Player player) {
        Lang lang = hub.lang();
        Menu menu = new Menu(HubSettings.MENU_SLOTS / 9, lang.get("selector.title"));
        for (Mode mode : hub.settings().modes()) {
            List<Component> lore = new ArrayList<>(lang.list("modes." + mode.id() + ".lore"));
            lore.add(Component.empty());
            lore.add(status(mode));
            lore.add(lang.get("selector.click"));
            menu.set(mode.slot(), Icons.icon(icon(mode), name(mode), lore), (p, click) -> {
                p.closeInventory();
                join(p, mode);
            });
        }
        menu.fill(Icons.filler());
        menu.open(player);
    }

    /** Sends the player to the mode's server group through the proxy's queue. */
    public void join(Player player, Mode mode) {
        long now = System.currentTimeMillis();
        Long last = lastJoin.get(player.getUniqueId());
        if (last != null && now - last < JOIN_COOLDOWN_MILLIS) {
            return;
        }
        lastJoin.put(player.getUniqueId(), now);
        hub.lang().send(player, "selector.joining", Placeholder.component("mode", name(mode)));
        RapidCore.get().connect(player, mode.group());
    }

    public void forget(Player player) {
        lastJoin.remove(player.getUniqueId());
    }

    private Material icon(Mode mode) {
        Material m = Material.matchMaterial(mode.icon());
        if (m == null || !m.isItem() || m.isAir()) {
            hub.getSLF4JLogger().warn("modes.{}.icon: '{}' isn't an item; showing a compass", mode.id(), mode.icon());
            return Material.COMPASS;
        }
        return m;
    }
}
