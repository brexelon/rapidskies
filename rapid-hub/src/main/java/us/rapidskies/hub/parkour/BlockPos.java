package us.rapidskies.hub.parkour;

/** A block position as written in config: {@code "x, y, z"}. */
public record BlockPos(int x, int y, int z) {

    public static BlockPos parse(String text) {
        String[] parts = text.split(",");
        if (parts.length != 3) {
            throw new IllegalArgumentException("expected \"x, y, z\", got \"" + text + "\"");
        }
        try {
            return new BlockPos(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()),
                    Integer.parseInt(parts[2].trim()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a whole number in block position \"" + text + "\"", e);
        }
    }

    public String format() {
        return x + ", " + y + ", " + z;
    }
}
