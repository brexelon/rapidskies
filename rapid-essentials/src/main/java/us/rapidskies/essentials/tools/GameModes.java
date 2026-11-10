package us.rapidskies.essentials.tools;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.command.CommandSender;

/** Game mode names for {@code /gamemode}, and the permission for switching to each one. */
public final class GameModes {

    /** In the order they're suggested. */
    public static final List<GameMode> ALL = List.of(GameMode.SURVIVAL, GameMode.CREATIVE, GameMode.ADVENTURE,
            GameMode.SPECTATOR);
    public static final String OTHERS = "rapid.command.gamemode.others";

    private static final Map<String, GameMode> NAMES = Map.ofEntries(
            Map.entry("survival", GameMode.SURVIVAL), Map.entry("surv", GameMode.SURVIVAL),
            Map.entry("s", GameMode.SURVIVAL), Map.entry("0", GameMode.SURVIVAL),
            Map.entry("creative", GameMode.CREATIVE), Map.entry("c", GameMode.CREATIVE),
            Map.entry("1", GameMode.CREATIVE),
            Map.entry("adventure", GameMode.ADVENTURE), Map.entry("adv", GameMode.ADVENTURE),
            Map.entry("a", GameMode.ADVENTURE), Map.entry("2", GameMode.ADVENTURE),
            Map.entry("spectator", GameMode.SPECTATOR), Map.entry("spec", GameMode.SPECTATOR),
            Map.entry("sp", GameMode.SPECTATOR), Map.entry("3", GameMode.SPECTATOR));

    private GameModes() {
    }

    /** {@code creative}, {@code c}, {@code 1}... ignoring case; null if it's no game mode. */
    public static GameMode parse(String input) {
        return input == null ? null : NAMES.get(input.trim().toLowerCase(Locale.ROOT));
    }

    /** {@code creative}. */
    public static String id(GameMode mode) {
        return mode.name().toLowerCase(Locale.ROOT);
    }

    /** {@code rapid.command.gamemode.creative}: switching anyone (yourself included) to that mode. */
    public static String permission(GameMode mode) {
        return "rapid.command.gamemode." + id(mode);
    }

    /** The mode's name in the reader's own language. */
    public static Component name(GameMode mode) {
        return Component.translatable("gameMode." + id(mode));
    }

    /** The modes {@code sender} may switch to. */
    public static List<GameMode> allowed(CommandSender sender) {
        return ALL.stream().filter(m -> sender.hasPermission(permission(m))).toList();
    }
}
