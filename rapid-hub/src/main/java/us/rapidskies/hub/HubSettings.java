package us.rapidskies.hub;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import us.rapidskies.common.config.ConfigView;
import us.rapidskies.core.world.SpawnPoint;
import us.rapidskies.hub.parkour.BlockPos;
import us.rapidskies.hub.parkour.Course;

/**
 * rapid-hub's config.yml: the gamemodes in the selector and on the NPCs, double jump, the
 * parkour courses and the leaderboards. Parsing collects every problem instead of stopping
 * at the first, and {@link #from} throws them together.
 */
public record HubSettings(List<Mode> modes, DoubleJump doubleJump, String parkourWorld, List<Course> courses,
                          List<Board> boards) {

    /** Lowercase ids: they're lang keys ({@code modes.<id>}) and database values (courses). */
    private static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,32}");
    public static final String DEFAULT_WORLD = "minecraft:overworld";
    public static final String DEFAULT_PARKOUR_WORLD = "rapidskies:parkour";
    public static final int MENU_SLOTS = 27;

    /**
     * A gamemode in the menu and on an NPC.
     *
     * @param group server group the proxy's queue knows
     * @param icon  item id for the menu (resolved at runtime; a bad one falls back to a compass)
     */
    public record Mode(String id, String group, String icon, int slot, Optional<SpawnPoint> npc, Skin skin) {
    }

    /** An NPC skin: by account name, or a fixed texture and signature. All empty: the default skin. */
    public record Skin(String name, String texture, String signature) {

        public boolean fixed() {
            return !texture.isEmpty() && !signature.isEmpty();
        }
    }

    public record DoubleJump(boolean enabled, double forward, double up) {
    }

    /** A leaderboard hologram ranking one course, all time or this week. */
    public record Board(String id, SpawnPoint location, String course, boolean weekly, int size) {
    }

    public Optional<Course> course(String id) {
        return courses.stream().filter(c -> c.id().equals(id)).findFirst();
    }

    public static HubSettings from(ConfigView c) {
        List<String> problems = new ArrayList<>();
        List<Mode> modes = modes(c.section("modes"), problems);
        DoubleJump jump = new DoubleJump(c.bool("double-jump.enabled", true),
                number(c, "double-jump.forward", 1.2, problems), number(c, "double-jump.up", 0.9, problems));
        String parkourWorld = c.string("parkour.world", DEFAULT_PARKOUR_WORLD).trim();
        if (parkourWorld.isEmpty() || parkourWorld.equals(DEFAULT_WORLD)) {
            problems.add("parkour.world: needs a world of its own (courses are built and removed there), not '"
                    + parkourWorld + "'");
        }
        List<Course> courses = courses(c.section("parkour.courses"), problems);
        List<Board> boards = boards(c.section("leaderboards"), courses, problems);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", problems));
        }
        return new HubSettings(List.copyOf(modes), jump, parkourWorld, List.copyOf(courses), List.copyOf(boards));
    }

    private static List<Mode> modes(ConfigView section, List<String> problems) {
        List<Mode> out = new ArrayList<>();
        List<Integer> slots = new ArrayList<>();
        for (String id : section.keys()) {
            String where = "modes." + id;
            if (!ID.matcher(id).matches()) {
                problems.add(where + ": ids are lowercase letters, digits, - and _");
                continue;
            }
            ConfigView m = section.section(id);
            String group = m.string("group", "").trim();
            if (group.isEmpty()) {
                problems.add(where + ".group: missing (the server group players join)");
            }
            int slot;
            try {
                slot = m.integer("slot", -1);
            } catch (IllegalArgumentException e) {
                problems.add(e.getMessage());
                continue;
            }
            if (slot < 0 || slot >= MENU_SLOTS) {
                problems.add(where + ".slot: expected 0 to " + (MENU_SLOTS - 1) + ", got " + slot);
            } else if (slots.contains(slot)) {
                problems.add(where + ".slot: " + slot + " is already taken by another mode");
            }
            slots.add(slot);
            Optional<SpawnPoint> npc = point(m.string("npc", ""), where + ".npc", problems);
            Skin skin = new Skin(m.string("skin", "").trim(), m.string("skin-texture", "").trim(),
                    m.string("skin-signature", "").trim());
            if (skin.texture().isEmpty() != skin.signature().isEmpty()) {
                problems.add(where + ": skin-texture and skin-signature go together");
            }
            out.add(new Mode(id, group, m.string("icon", "compass").trim().toLowerCase(Locale.ROOT), slot, npc, skin));
        }
        return out;
    }

    private static List<Course> courses(ConfigView section, List<String> problems) {
        List<Course> out = new ArrayList<>();
        for (String id : section.keys()) {
            String where = "parkour.courses." + id;
            if (!ID.matcher(id).matches()) {
                problems.add(where + ": ids are lowercase letters, digits, - and _");
                continue;
            }
            ConfigView c = section.section(id);
            int length;
            int every;
            try {
                length = c.integer("length", 150);
                every = c.integer("checkpoint-every", 50);
            } catch (IllegalArgumentException e) {
                problems.add(e.getMessage());
                continue;
            }
            if (length < Course.MIN_LENGTH || length > Course.MAX_LENGTH) {
                problems.add(where + ".length: expected " + Course.MIN_LENGTH + " to " + Course.MAX_LENGTH
                        + " blocks, got " + length);
            }
            if (every < Course.MIN_CHECKPOINT_EVERY) {
                problems.add(where + ".checkpoint-every: at least " + Course.MIN_CHECKPOINT_EVERY + " blocks, got "
                        + every);
                continue;
            }
            Optional<BlockPos> entrance = block(c.string("entrance", ""), where + ".entrance", problems);
            out.add(new Course(id, length, every, entrance));
        }
        return out;
    }

    private static List<Board> boards(ConfigView section, List<Course> courses, List<String> problems) {
        List<Board> out = new ArrayList<>();
        for (String id : section.keys()) {
            String where = "leaderboards." + id;
            ConfigView b = section.section(id);
            Optional<SpawnPoint> location = point(b.string("location", ""), where + ".location", problems);
            String course = b.string("course", "").trim();
            if (courses.stream().noneMatch(c -> c.id().equals(course))) {
                problems.add(where + ".course: no parkour course '" + course + "'");
            }
            int size;
            try {
                size = b.integer("size", 10);
            } catch (IllegalArgumentException e) {
                problems.add(e.getMessage());
                continue;
            }
            if (size < 1 || size > 15) {
                problems.add(where + ".size: expected 1 to 15 lines, got " + size);
            }
            if (location.isEmpty()) {
                problems.add(where + ".location: missing");
                continue;
            }
            out.add(new Board(id, location.get(), course, b.bool("weekly", false), size));
        }
        return out;
    }

    private static Optional<SpawnPoint> point(String text, String where, List<String> problems) {
        if (text.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(SpawnPoint.parse(text));
        } catch (IllegalArgumentException e) {
            problems.add(where + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    private static Optional<BlockPos> block(String text, String where, List<String> problems) {
        if (text.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(BlockPos.parse(text));
        } catch (IllegalArgumentException e) {
            problems.add(where + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    private static double number(ConfigView c, String path, double def, List<String> problems) {
        Object v = c.get(path);
        if (v == null) {
            return def;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            problems.add(path + ": expected a number, got '" + v + "'");
            return def;
        }
    }
}
