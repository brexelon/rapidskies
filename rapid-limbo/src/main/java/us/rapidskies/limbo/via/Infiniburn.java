package us.rapidskies.limbo.via;

/**
 * The block tag a pre-26.2 client expects in a dimension type's {@code infiniburn}.
 *
 * <p>26.2 dropped the {@code #minecraft:infiniburn_*} block tags and lists the blocks inline,
 * which is how Limbo 26.3 ships its dimension types. Clients before 26.2 only accept a tag
 * there, and ViaBackwards passes the list through unchanged, so they fail to decode the
 * registry and are disconnected with the whole registry as the reason. The client never
 * resolves the tag (it is only used for fire, server-side), so naming it is enough.
 */
final class Infiniburn {

    private Infiniburn() {
    }

    /** The vanilla tag for the dimension type {@code key} ({@code minecraft:the_end}, {@code the_nether}, ...). */
    static String tagFor(String key) {
        String path = key.startsWith("minecraft:") ? key.substring("minecraft:".length()) : key;
        return switch (path) {
            case "the_nether" -> "#minecraft:infiniburn_nether";
            case "the_end" -> "#minecraft:infiniburn_end";
            default -> "#minecraft:infiniburn_overworld";
        };
    }
}
