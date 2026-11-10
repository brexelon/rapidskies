package us.rapidskies.essentials.home;

import com.destroystokyo.paper.event.player.PlayerConnectionCloseEvent;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import us.rapidskies.essentials.EssentialsSettings;
import us.rapidskies.essentials.RapidEssentials;
import us.rapidskies.essentials.data.EssentialsRepository;
import us.rapidskies.essentials.data.Names;
import us.rapidskies.essentials.data.Place;
import us.rapidskies.essentials.teleport.TeleportKind;

/**
 * {@code /home}, {@code /sethome}, {@code /delhome} and {@code /homes}. Homes belong to the
 * server group (a Horizons home is only offered on Horizons), and everyone gets the same
 * number ({@code homes.limit}): extra homes are never sold (PLAN.md §6.1).
 *
 * <p>A player's homes are loaded before they join and kept while they're on, so commands
 * and tab completion never wait on the database; changes are written through.
 */
public final class HomeService implements Listener {

    public static final String DEFAULT_NAME = "home";

    private final RapidEssentials plugin;
    private final EssentialsRepository repository;
    private final boolean enabled;
    /** Immutable maps, replaced whole on change. */
    private final Map<UUID, Map<String, Place>> cache = new ConcurrentHashMap<>();

    /** {@code enabled}: whether this server offers homes at all (not on the hub or SkyRealms). */
    public HomeService(RapidEssentials plugin, EssentialsRepository repository, boolean enabled) {
        this.plugin = plugin;
        this.repository = repository;
        this.enabled = enabled;
    }

    private EssentialsSettings.Homes settings() {
        return plugin.settings().homes();
    }

    // ------------------------------------------------------------------ loading

    // MONITOR: only logins that will go ahead. Async thread, so blocking is fine.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (!enabled || event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        try {
            cache.put(event.getUniqueId(), sorted(repository.homes(plugin.scope(), event.getUniqueId())));
        } catch (SQLException e) {
            // /home retries the load.
            plugin.getSLF4JLogger().warn("Couldn't load the homes of {}: {}", event.getName(), e.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onConnectionClose(PlayerConnectionCloseEvent event) {
        cache.remove(event.getPlayerUniqueId());
    }

    /**
     * {@code player}'s homes, or null (after telling them and starting a reload) if they
     * couldn't be loaded at login.
     */
    private Map<String, Place> homes(Player player) {
        Map<String, Place> homes = cache.get(player.getUniqueId());
        if (homes == null) {
            plugin.lang().send(player, "homes.loading");
            UUID id = player.getUniqueId();
            plugin.core().database().async(() -> repository.homes(plugin.scope(), id))
                    .thenAccept(loaded -> cache.put(id, sorted(loaded)))
                    .exceptionally(e -> {
                        plugin.getSLF4JLogger().warn("Couldn't load the homes of {}: {}", player.getName(),
                                e.getMessage());
                        return null;
                    });
        }
        return homes;
    }

    /** Home names of {@code player}, for tab completion. */
    public List<String> names(Player player) {
        return List.copyOf(cache.getOrDefault(player.getUniqueId(), Map.of()).keySet());
    }

    // ------------------------------------------------------------------ commands

    /** {@code /home [name]}: no name takes the only home, or the one called "home", or lists them. */
    public void home(Player player, String input) {
        Map<String, Place> homes = homes(player);
        if (homes == null) {
            return;
        }
        if (homes.isEmpty()) {
            plugin.lang().send(player, "homes.none");
            return;
        }
        String name;
        if (input == null) {
            if (homes.size() == 1) {
                name = homes.keySet().iterator().next();
            } else if (homes.containsKey(DEFAULT_NAME)) {
                name = DEFAULT_NAME;
            } else {
                list(player);
                return;
            }
        } else {
            name = Names.normalize(input).orElse(null);
        }
        Place place = name == null ? null : homes.get(name);
        if (place == null) {
            plugin.lang().send(player, "homes.unknown", Placeholder.unparsed("name", input));
            return;
        }
        if (place.toLocation() == null) {
            plugin.lang().send(player, "homes.world-gone", Placeholder.unparsed("name", name));
            return;
        }
        String shown = name;
        plugin.teleports().teleport(player, TeleportKind.HOME, place::toLocation, true,
                () -> plugin.lang().send(player, "homes.arrived", Placeholder.unparsed("name", shown)));
    }

    /** {@code /sethome [name]}: here, under {@code name} (default "home"). */
    public void set(Player player, String input) {
        String raw = input == null ? DEFAULT_NAME : input;
        Optional<String> name = Names.normalize(raw);
        if (name.isEmpty()) {
            plugin.lang().send(player, "homes.invalid-name",
                    Placeholder.unparsed("max", Integer.toString(Names.MAX_LENGTH)));
            return;
        }
        Location at = player.getLocation();
        if (settings().blocked(at.getWorld().getKey().asString())) {
            plugin.lang().send(player, "homes.blocked-world");
            return;
        }
        if (homes(player) == null) {
            return;
        }
        int limit = settings().limit();
        UUID id = player.getUniqueId();
        Place place = Place.of(at);
        String home = name.get();
        plugin.core().database().async(() -> repository.setHome(plugin.scope(), id, home, place, limit))
                .thenAccept(result -> {
                    var nameTag = Placeholder.unparsed("name", home);
                    var limitTag = Placeholder.unparsed("limit", Integer.toString(limit));
                    switch (result) {
                        case CREATED, MOVED -> {
                            cache.computeIfPresent(id, (k, old) -> {
                                Map<String, Place> next = new TreeMap<>(old);
                                next.put(home, place);
                                return sorted(next);
                            });
                            plugin.lang().send(player, result == EssentialsRepository.SetHome.CREATED
                                    ? "homes.set" : "homes.moved", nameTag);
                        }
                        case LIMIT -> plugin.lang().send(player, "homes.limit", limitTag);
                    }
                })
                .exceptionally(e -> failed(player, e));
    }

    /** {@code /delhome <name>}. */
    public void delete(Player player, String input) {
        Optional<String> name = Names.normalize(input);
        Map<String, Place> homes = homes(player);
        if (homes == null) {
            return;
        }
        if (name.isEmpty() || !homes.containsKey(name.get())) {
            plugin.lang().send(player, "homes.unknown", Placeholder.unparsed("name", input));
            return;
        }
        UUID id = player.getUniqueId();
        String home = name.get();
        plugin.core().database().async(() -> repository.deleteHome(plugin.scope(), id, home))
                .thenAccept(deleted -> {
                    cache.computeIfPresent(id, (k, old) -> {
                        Map<String, Place> next = new TreeMap<>(old);
                        next.remove(home);
                        return sorted(next);
                    });
                    plugin.lang().send(player, "homes.deleted", Placeholder.unparsed("name", home));
                })
                .exceptionally(e -> failed(player, e));
    }

    /** {@code /homes}: each name clickable. */
    public void list(Player player) {
        Map<String, Place> homes = homes(player);
        if (homes == null) {
            return;
        }
        if (homes.isEmpty()) {
            plugin.lang().send(player, "homes.none");
            return;
        }
        Component entries = Component.join(JoinConfiguration.separator(plugin.lang().get("homes.separator")),
                homes.keySet().stream().map(name -> plugin.lang().get("homes.entry",
                        Placeholder.unparsed("name", name),
                        Placeholder.styling("go", ClickEvent.runCommand("/home " + name)))).toList());
        plugin.lang().send(player, "homes.list", Placeholder.component("homes", entries),
                Placeholder.unparsed("count", Integer.toString(homes.size())),
                Placeholder.unparsed("limit", Integer.toString(settings().limit())));
    }

    private Void failed(Player player, Throwable e) {
        plugin.getSLF4JLogger().warn("A home change for {} failed", player.getName(), e);
        plugin.lang().send(player, "homes.failed");
        return null;
    }

    private static Map<String, Place> sorted(Map<String, Place> homes) {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(new TreeMap<>(homes)));
    }
}
