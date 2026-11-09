package us.rapidskies.hub.parkour;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;

/**
 * The lanes of the parkour world: each run gets its own, {@link #SPACING} blocks from the
 * next, so players never see (or land on) each other's courses. A lane's blocks are
 * written to a small file while it's in use, so blocks a crash left behind are cleared on
 * the next start instead of piling up in the world.
 */
final class Lanes {

    /** Blocks between lane lines (z); more than any hub's view distance. */
    static final int SPACING = 512;
    /** Height of every start platform. */
    static final int BASE_Y = 100;

    private final Path file;
    private final Logger log;
    private final Map<Integer, List<BlockPos>> used = new TreeMap<>();

    Lanes(Path file, Logger log) {
        this.file = file;
        this.log = log;
    }

    /** Where lane {@code lane}'s start platform is centred. */
    static BlockPos origin(int lane) {
        return new BlockPos(0, BASE_Y, lane * SPACING);
    }

    /** Takes the lowest free lane for a course whose blocks will be at {@code blocks} (absolute). */
    int claim(java.util.function.IntFunction<List<BlockPos>> blocks) {
        int lane = 0;
        while (used.containsKey(lane)) {
            lane++;
        }
        used.put(lane, List.copyOf(blocks.apply(lane)));
        save();
        return lane;
    }

    void release(int lane) {
        if (used.remove(lane) != null) {
            save();
        }
    }

    /** Lanes the last run of the server left in use (a crash), with their blocks; forgets them. */
    Map<Integer, List<BlockPos>> leftovers() {
        Map<Integer, List<BlockPos>> out = new TreeMap<>();
        if (!Files.exists(file)) {
            return out;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                int colon = line.indexOf(':');
                if (colon < 0) {
                    continue;
                }
                List<BlockPos> blocks = new ArrayList<>();
                for (String pos : line.substring(colon + 1).split(";")) {
                    if (!pos.isBlank()) {
                        blocks.add(BlockPos.parse(pos));
                    }
                }
                out.put(Integer.parseInt(line.substring(0, colon).trim()), blocks);
            }
        } catch (IOException | IllegalArgumentException e) {
            log.warn("Couldn't read {}; old parkour blocks may be left in the parkour world: {}", file, e.getMessage());
        }
        used.clear();
        save();
        return out;
    }

    private void save() {
        StringBuilder out = new StringBuilder();
        used.forEach((lane, blocks) -> {
            out.append(lane).append(':');
            for (BlockPos p : blocks) {
                out.append(p.x()).append(',').append(p.y()).append(',').append(p.z()).append(';');
            }
            out.append('\n');
        });
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, out, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Couldn't write {}: {}", file, e.getMessage());
        }
    }
}
