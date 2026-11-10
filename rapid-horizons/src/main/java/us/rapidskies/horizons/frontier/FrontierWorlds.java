package us.rapidskies.horizons.frontier;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.slf4j.Logger;
import us.rapidskies.horizons.HorizonsSettings;
import us.rapidskies.horizons.world.WorldHost;

/**
 * Creating, loading, unloading and archiving a Frontier's three worlds. Main thread, except
 * {@link #archive} and {@link #delete}, which move files and belong on a worker.
 *
 * <p>The worlds themselves go through the {@link WorldHost}: Multiverse-Core when it's
 * installed, which records the overworld's generator ({@code rapid-horizons:<modifier>}) in its
 * {@code worlds.yml}, else Bukkit. Either way the rotation decides when they load.
 *
 * <p>Worlds live where Paper puts keyed worlds ({@code <level>/dimensions/rapidskies/frontier_<id>}).
 * A closed Frontier's folders move to {@code <archive-folder>/frontier_<id>/} with an
 * {@code ARCHIVED_AT} file, and are deleted {@code archive-days} later (PLAN.md §3: archive
 * each retired Frontier for a month).
 */
final class FrontierWorlds {

    private final Logger log;
    private final WorldHost host;

    FrontierWorlds(Logger log, WorldHost host) {
        this.log = log;
        this.host = host;
    }

    /** The loaded world with this key, or null. */
    static World loaded(String key) {
        NamespacedKey k = NamespacedKey.fromString(key);
        return k == null ? null : Bukkit.getWorld(k);
    }

    /** Loads (creating if new) the Frontier overworld with its modifier's generator. */
    World overworld(FrontierRow row, HorizonsSettings.Frontier settings) {
        boolean drought = row.modifier() == Modifier.DROUGHT;
        World w = host.load(new WorldHost.Spec(key(row.worldKey()), World.Environment.NORMAL, row.seed(),
                Optional.of(row.modifier()), "Frontier " + row.id() + " (" + row.modifier().id() + ")", !drought));
        border(w, settings.borderSize());
        if (drought) {
            rule(w, "advance_weather", false);
            w.setStorm(false);
            w.setThundering(false);
        }
        return w;
    }

    World nether(FrontierRow row, HorizonsSettings.Frontier settings) {
        return plain(row, row.netherKey(), World.Environment.NETHER, row.seed() ^ 0x4E45544845524CL,
                settings.netherBorderSize(), "Nether");
    }

    World end(FrontierRow row, HorizonsSettings.Frontier settings) {
        return plain(row, row.endKey(), World.Environment.THE_END, row.seed() ^ 0x454E44L, settings.endBorderSize(),
                "End");
    }

    private World plain(FrontierRow row, String key, World.Environment environment, long seed, int borderSize,
                        String what) {
        World w = host.load(new WorldHost.Spec(key(key), environment, seed, Optional.empty(),
                "Frontier " + row.id() + " " + what, true));
        border(w, borderSize);
        return w;
    }

    private static NamespacedKey key(String key) {
        NamespacedKey k = NamespacedKey.fromString(key);
        if (k == null) {
            throw new IllegalArgumentException("Not a world key: " + key);
        }
        return k;
    }

    private static void border(World w, int size) {
        w.getWorldBorder().setCenter(0, 0);
        w.getWorldBorder().setSize(size);
    }

    private void rule(World w, String name, boolean value) {
        @SuppressWarnings("unchecked")
        GameRule<Boolean> rule = (GameRule<Boolean>) Registry.GAME_RULE.get(NamespacedKey.minecraft(name));
        if (rule == null) {
            log.warn("No game rule {} on this version", name);
            return;
        }
        w.setGameRule(rule, value);
    }

    /**
     * Unloads {@code row}'s worlds (saving them) and returns their folders. Players must be out
     * already; a world that won't unload is left loaded and logged.
     */
    List<Path> unload(FrontierRow row) {
        List<Path> folders = new ArrayList<>();
        for (String key : List.of(row.worldKey(), row.netherKey(), row.endKey())) {
            World w = loaded(key);
            if (w == null) {
                continue;
            }
            Path folder = w.getWorldPath();
            if (host.unload(w)) {
                folders.add(folder);
            } else {
                log.error("Couldn't unload {}; it stays loaded until the next restart", key);
            }
        }
        return folders;
    }

    /** Moves the folders into {@code archiveRoot/frontier_<id>/}; returns that folder. Worker thread. */
    Path archive(int id, List<Path> folders, Path archiveRoot) throws IOException {
        Path target = archiveRoot.resolve("frontier_" + id);
        Files.createDirectories(target);
        for (Path folder : folders) {
            if (Files.isDirectory(folder)) {
                Path to = target.resolve(folder.getFileName());
                if (Files.exists(to)) {
                    delete(to);
                }
                Files.move(folder, to);
            }
        }
        Files.writeString(target.resolve("ARCHIVED_AT"), Instant.now().toString());
        return target;
    }

    /** Deletes a folder tree (an expired archive). Worker thread. */
    static void delete(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
