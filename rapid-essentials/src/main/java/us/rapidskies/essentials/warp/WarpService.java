package us.rapidskies.essentials.warp;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import us.rapidskies.essentials.RapidEssentials;
import us.rapidskies.essentials.data.EssentialsRepository;
import us.rapidskies.essentials.data.Names;
import us.rapidskies.essentials.data.Place;
import us.rapidskies.essentials.teleport.TeleportKind;

/**
 * Server warps: places staff name with {@code /setwarp} ({@code rapid.command.setwarp}) and
 * everyone reaches with {@code /warp <name>}. Per server group, like homes. Loaded at
 * startup and kept in memory; changes are written through.
 */
public final class WarpService {

    public static final String SET_PERMISSION = "rapid.command.setwarp";

    private final RapidEssentials plugin;
    private final EssentialsRepository repository;
    /** Immutable, sorted by name; replaced whole on change. */
    private volatile Map<String, Place> warps = Map.of();

    public WarpService(RapidEssentials plugin, EssentialsRepository repository) {
        this.plugin = plugin;
        this.repository = repository;
    }

    /** Loads the warps in the background. Until then, there are none. */
    public void load() {
        plugin.core().database().async(() -> repository.warps(plugin.scope()))
                .thenAccept(loaded -> warps = sorted(loaded))
                .exceptionally(e -> {
                    plugin.getSLF4JLogger().error("Couldn't load the warps; /warp has none until a restart", e);
                    return null;
                });
    }

    public List<String> names() {
        return List.copyOf(warps.keySet());
    }

    /** {@code /warp [name]}: no name lists them. */
    public void warp(Player player, String input) {
        if (input == null) {
            list(player);
            return;
        }
        String name = Names.normalize(input).orElse(null);
        Place place = name == null ? null : warps.get(name);
        if (place == null) {
            plugin.lang().send(player, "warps.unknown", Placeholder.unparsed("name", input));
            return;
        }
        if (place.toLocation() == null) {
            plugin.lang().send(player, "warps.world-gone", Placeholder.unparsed("name", name));
            return;
        }
        plugin.teleports().teleport(player, TeleportKind.WARP, place::toLocation, false,
                () -> plugin.lang().send(player, "warps.arrived", Placeholder.unparsed("name", name)));
    }

    public void list(Player player) {
        Map<String, Place> all = warps;
        if (all.isEmpty()) {
            plugin.lang().send(player, "warps.none");
            return;
        }
        Component entries = Component.join(JoinConfiguration.separator(plugin.lang().get("warps.separator")),
                all.keySet().stream().map(name -> plugin.lang().get("warps.entry",
                        Placeholder.unparsed("name", name),
                        Placeholder.styling("go", ClickEvent.runCommand("/warp " + name)))).toList());
        plugin.lang().send(player, "warps.list", Placeholder.component("warps", entries),
                Placeholder.unparsed("count", Integer.toString(all.size())));
    }

    /** {@code /setwarp <name>}: here. */
    public void set(Player player, String input) {
        Optional<String> name = Names.normalize(input);
        if (name.isEmpty()) {
            plugin.lang().send(player, "warps.invalid-name",
                    Placeholder.unparsed("max", Integer.toString(Names.MAX_LENGTH)));
            return;
        }
        Place place = Place.of(player.getLocation());
        String warp = name.get();
        plugin.core().database().async(() -> repository.setWarp(plugin.scope(), warp, place, player.getUniqueId()))
                .thenAccept(existed -> {
                    update(warp, place);
                    plugin.lang().send(player, existed ? "warps.moved" : "warps.set", Placeholder.unparsed("name", warp));
                })
                .exceptionally(e -> failed(player, e));
    }

    /** {@code /delwarp <name>}. */
    public void delete(Player player, String input) {
        String name = Names.normalize(input).orElse(null);
        if (name == null || !warps.containsKey(name)) {
            plugin.lang().send(player, "warps.unknown", Placeholder.unparsed("name", input));
            return;
        }
        plugin.core().database().async(() -> repository.deleteWarp(plugin.scope(), name))
                .thenAccept(deleted -> {
                    update(name, null);
                    plugin.lang().send(player, "warps.deleted", Placeholder.unparsed("name", name));
                })
                .exceptionally(e -> failed(player, e));
    }

    private synchronized void update(String name, Place place) {
        Map<String, Place> next = new TreeMap<>(warps);
        if (place == null) {
            next.remove(name);
        } else {
            next.put(name, place);
        }
        warps = sorted(next);
    }

    private Void failed(Player player, Throwable e) {
        plugin.getSLF4JLogger().warn("A warp change by {} failed", player.getName(), e);
        plugin.lang().send(player, "warps.failed");
        return null;
    }

    private static Map<String, Place> sorted(Map<String, Place> warps) {
        return Collections.unmodifiableMap(new java.util.LinkedHashMap<>(new TreeMap<>(warps)));
    }
}
