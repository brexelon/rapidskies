package us.rapidskies.core.display;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.Plugin;
import us.rapidskies.core.world.SpawnPoint;

/**
 * Floating text (NPC labels, leaderboards) as text display entities, which every supported
 * client (1.20+) renders; each rapid-* plugin that shows some keeps its own instance. They're
 * never saved with the world: each start spawns them fresh from the config, so they can't
 * pile up or outlive a config change. Their chunks get a plugin ticket, so they stay loaded
 * (and the labels with them) while nobody is near.
 */
public final class Labels {

    private final Plugin plugin;
    private final List<TextDisplay> spawned = new ArrayList<>();
    private final Set<World> ticketWorlds = new HashSet<>();

    public Labels(Plugin plugin) {
        this.plugin = plugin;
    }

    /** A world by key ({@code minecraft:overworld}) or, failing that, by name. */
    public static World world(String ref) {
        if (ref.indexOf(':') >= 0) {
            NamespacedKey key = NamespacedKey.fromString(ref);
            return key == null ? null : Bukkit.getWorld(key);
        }
        return Bukkit.getWorld(ref);
    }

    /** The point as a location, or null if its world isn't loaded. */
    public static Location location(SpawnPoint point) {
        World world = world(point.world());
        return world == null ? null : new Location(world, point.x(), point.y(), point.z(), point.yaw(), point.pitch());
    }

    /** Keeps the chunk at {@code at} loaded until {@link #removeAll}. */
    public void keepLoaded(Location at) {
        World world = at.getWorld();
        world.addPluginChunkTicket(at.getBlockX() >> 4, at.getBlockZ() >> 4, plugin);
        ticketWorlds.add(world);
    }

    /** A centred label that always faces the viewer; {@code at} is the bottom of the text. */
    public TextDisplay spawn(Location at, Component text) {
        keepLoaded(at);
        TextDisplay display = at.getWorld().spawn(at, TextDisplay.class, d -> {
            d.setPersistent(false);
            d.text(text);
            d.setBillboard(Display.Billboard.CENTER);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setShadowed(true);
            d.setLineWidth(400);
            d.setDefaultBackground(false);
            d.setBackgroundColor(Color.fromARGB(80, 0, 0, 0));
        });
        spawned.add(display);
        return display;
    }

    /** Removes every label and releases the chunks. */
    public void removeAll() {
        spawned.forEach(TextDisplay::remove);
        spawned.clear();
        ticketWorlds.forEach(w -> w.removePluginChunkTickets(plugin));
        ticketWorlds.clear();
    }
}
