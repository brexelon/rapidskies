package us.rapidskies.skyrealms.island;

import com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.key.Key;
import com.bgsoftware.superiorskyblock.api.schematic.Schematic;
import com.bgsoftware.superiorskyblock.api.schematic.parser.SchematicParseException;
import com.bgsoftware.superiorskyblock.api.schematic.parser.SchematicParser;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;
import us.rapidskies.skyrealms.SkyRealmsSettings.ItemAmount;

/**
 * Registers the code-built starter islands ({@link IslandLayouts}) with SuperiorSkyblock2.
 *
 * <p>SuperiorSkyblock2 only knows schematics it reads from files in its {@code schematics/}
 * folder, and asks parsers registered through its API before its own. So for each layout this
 * writes a small marker file ({@code meadow.schematic}: {@value #MAGIC} and the id, gzipped like
 * every SuperiorSkyblock2 schematic, since it unzips each file before any parser sees it) and
 * registers a parser that turns those markers into a {@link LayoutSchematic}. Both happen in
 * rapid-skyrealms' {@code onLoad}: SuperiorSkyblock2 (a {@code depend}) has loaded by then, and
 * reads its schematics during its own {@code onEnable}, which comes after every plugin's onLoad.
 *
 * <p>A file with a layout's name that isn't a marker (a schematic an admin saved in game) is
 * left alone, with a warning: it wins over the layout.
 */
public final class IslandSchematics {

    static final String MAGIC = "RAPIDSKIES-LAYOUT ";

    private IslandSchematics() {
    }

    /**
     * Writes the marker files and registers the parser. {@code chest} is read at paste time, so
     * a reload changes what new islands get. {@code plugin} is only used for scheduling, so it may
     * not be enabled yet.
     */
    public static void install(Plugin plugin, List<String> layouts, Supplier<List<ItemAmount>> chest, Logger log) {
        Path dir = plugin.getDataPath().getParent().resolve("SuperiorSkyblock2").resolve("schematics");
        try {
            Files.createDirectories(dir);
            for (String id : layouts) {
                Path file = dir.resolve(id + ".schematic");
                byte[] marker = marker(id);
                if (Files.exists(file)) {
                    byte[] existing = Files.readAllBytes(file);
                    if (java.util.Arrays.equals(existing, marker)) {
                        continue;
                    }
                    // A marker of ours in an older form (plain text, or another layout id) is
                    // rewritten; anything else is a real schematic and wins.
                    if (markerId(unpack(existing)) == null) {
                        log.warn("{} isn't rapid-skyrealms' marker (an in-game schematic?); the '{}' layout is "
                                + "skipped while it's there", file, id);
                        continue;
                    }
                }
                Files.write(file, marker);
            }
        } catch (IOException e) {
            log.error("Couldn't write the island layout markers to {}: {}", dir, e.getMessage());
            return;
        }
        Set<String> known = new HashSet<>(layouts);
        SuperiorSkyblockAPI.getSchematics().registerSchematicParser(new Parser(plugin, known, chest));
        log.info("Island layouts {} registered with SuperiorSkyblock2", layouts);
    }

    /**
     * A marker file's bytes: {@value #MAGIC} and the id, gzipped. SuperiorSkyblock2 opens every
     * schematic file as GZIP before handing it to any parser, so a plain-text marker fails
     * before the parser is asked.
     */
    static byte[] marker(String id) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(out)) {
            gz.write((MAGIC + id + "\n").getBytes(StandardCharsets.US_ASCII));
        }
        return out.toByteArray();
    }

    /** The first bytes of a file's content: unzipped if it's gzip, as it is otherwise. */
    static byte[] unpack(byte[] file) {
        if (file.length >= 2 && (file[0] & 0xFF) == 0x1f && (file[1] & 0xFF) == 0x8b) {
            try (java.util.zip.GZIPInputStream in = new java.util.zip.GZIPInputStream(
                    new java.io.ByteArrayInputStream(file))) {
                return in.readNBytes(64);
            } catch (IOException e) {
                return new byte[0];
            }
        }
        return java.util.Arrays.copyOf(file, Math.min(file.length, 64));
    }

    /** Reads a marker's (unzipped) layout id, or null if it's not a marker. */
    static String markerId(byte[] head) {
        String s = new String(head, StandardCharsets.ISO_8859_1);
        if (!s.startsWith(MAGIC)) {
            return null;
        }
        String id = s.substring(MAGIC.length()).trim();
        return id.isEmpty() ? null : id;
    }

    private record Parser(Plugin plugin, Set<String> known, Supplier<List<ItemAmount>> chest)
            implements SchematicParser {

        @Override
        public Schematic parseSchematic(DataInputStream in, String name) throws SchematicParseException {
            byte[] head;
            try {
                head = in.readNBytes(64);
            } catch (IOException e) {
                throw new SchematicParseException("can't read " + name + ": " + e.getMessage());
            }
            String id = markerId(head);
            if (id == null) {
                // A real schematic: SuperiorSkyblock2's own parser reads it next.
                throw new SchematicParseException(name + " isn't a rapid-skyrealms layout");
            }
            if (!known.contains(id)) {
                throw new SchematicParseException("layout '" + id + "' isn't in islands.layouts");
            }
            return new LayoutSchematic(plugin, name, IslandLayouts.build(id), chest);
        }
    }

    /** A starter island pasted block by block from its {@link Blueprint}. */
    static final class LayoutSchematic implements Schematic {

        private final Plugin plugin;
        private final String name;
        private final IslandLayouts.Layout layout;
        private final Supplier<List<ItemAmount>> chest;
        private Map<Key, Integer> counts;

        LayoutSchematic(Plugin plugin, String name, IslandLayouts.Layout layout, Supplier<List<ItemAmount>> chest) {
            this.plugin = plugin;
            this.name = name;
            this.layout = layout;
            this.chest = chest;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public void pasteSchematic(Island island, Location location, Runnable callback) {
            pasteSchematic(island, location, callback, null);
        }

        /**
         * Loads the chunks the island covers (asynchronously), then places every block in one
         * main-thread task, fills the starter chest and calls back. The origin is the block the
         * new member stands in.
         */
        @Override
        public void pasteSchematic(Island island, Location location, Runnable callback, Consumer<Throwable> onFailure) {
            World world = location.getWorld();
            int ox = location.getBlockX();
            int oy = location.getBlockY();
            int oz = location.getBlockZ();
            Set<Long> chunks = new HashSet<>();
            for (Blueprint.Block b : layout.blueprint().blocks()) {
                chunks.add(((long) ((ox + b.x()) >> 4) << 32) | (((oz + b.z()) >> 4) & 0xFFFFFFFFL));
            }
            List<CompletableFuture<?>> loads = new ArrayList<>();
            for (long c : chunks) {
                loads.add(world.getChunkAtAsync((int) (c >> 32), (int) c));
            }
            CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new)).whenComplete((v, err) ->
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        try {
                            if (err != null) {
                                throw err;
                            }
                            place(world, ox, oy, oz);
                            callback.run();
                        } catch (Throwable t) {
                            if (onFailure != null) {
                                onFailure.accept(t);
                            } else {
                                plugin.getSLF4JLogger().error("Pasting island layout {} failed", name, t);
                            }
                        }
                    }));
        }

        private void place(World world, int ox, int oy, int oz) {
            for (Blueprint.Block b : layout.blueprint().blocks()) {
                world.getBlockAt(ox + b.x(), oy + b.y(), oz + b.z())
                        .setBlockData(Bukkit.createBlockData(b.data()), false);
            }
            BlockState state = world.getBlockAt(ox + layout.chestX(), oy + layout.chestY(), oz + layout.chestZ())
                    .getState();
            if (state instanceof Chest box) {
                List<ItemStack> items = new ArrayList<>();
                for (ItemAmount a : chest.get()) {
                    if (a.max() > 0) {
                        items.add(ItemStack.of(a.material(), a.max()));
                    }
                }
                items.add(ItemStack.of(layout.sapling(), 2));
                box.getBlockInventory().addItem(items.toArray(ItemStack[]::new));
            }
        }

        @Override
        public Location adjustRotation(Location location) {
            Location out = location.clone();
            out.setYaw(layout.yaw());
            out.setPitch(0);
            return out;
        }

        @Override
        public Map<Key, Integer> getBlockCounts() {
            if (counts == null) {
                Map<Key, Integer> out = new HashMap<>();
                for (Map.Entry<Material, Integer> e : layout.blueprint().counts().entrySet()) {
                    out.merge(Key.of(e.getKey()), e.getValue(), Integer::sum);
                }
                counts = out;
            }
            return counts;
        }
    }
}
