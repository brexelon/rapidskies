package us.rapidskies.claims.gui;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import us.rapidskies.claims.RapidClaims;
import us.rapidskies.claims.model.ChunkKey;
import us.rapidskies.claims.model.Claim;
import us.rapidskies.claims.model.Flag;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.core.gui.Icons;
import us.rapidskies.core.gui.Menu;

/**
 * {@code /claim flags}: the flags of the chunk the player stands in (PLAN.md §4.1, claim
 * flags). Click to switch one for this chunk; shift-click to switch it on all the land this
 * chunk belongs with. Explosions aren't here: they're always blocked in claims.
 */
public final class FlagsMenu extends Menu {

    private static final int[] SLOTS = {10, 12, 14, 16};
    private static final Material[] ICONS = {
        Material.IRON_SWORD, Material.ZOMBIE_HEAD, Material.FLINT_AND_STEEL, Material.OAK_LEAVES};

    private final RapidClaims plugin;
    private final ChunkKey chunk;
    private final Lang lang;

    public FlagsMenu(RapidClaims plugin, ChunkKey chunk) {
        super(3, plugin.lang().get("flags.title", Placeholder.unparsed("x", Integer.toString(chunk.x())),
                Placeholder.unparsed("z", Integer.toString(chunk.z()))));
        this.plugin = plugin;
        this.chunk = chunk;
        this.lang = plugin.lang();
        if (SLOTS.length != Flag.values().length || ICONS.length != Flag.values().length) {
            throw new IllegalStateException("FlagsMenu needs a slot and an icon per Flag");
        }
    }

    @Override
    protected void render(Player viewer) {
        Claim claim = plugin.land().claim(chunk).orElse(null);
        if (claim == null) {
            viewer.closeInventory();
            return;
        }
        Flag[] flags = Flag.values();
        for (int i = 0; i < flags.length; i++) {
            Flag f = flags[i];
            boolean on = claim.flag(f);
            List<Component> lore = new java.util.ArrayList<>(lang.list("flag." + f.id() + ".lore"));
            lore.add(lang.get(on ? "flags.state-on" : "flags.state-off"));
            lore.addAll(lang.list("flags.click"));
            var icon = Icons.icon(ICONS[i], lang.get("flag." + f.id() + ".name"), lore);
            set(SLOTS[i], on ? Icons.glow(icon) : icon, (p, click) -> {
                if (!plugin.lands().canManage(p, chunk)) {
                    lang.send(p, "flags.not-allowed");
                    p.closeInventory();
                    return;
                }
                plugin.lands().setFlag(p, plugin.lands().flagScope(chunk, click.isShiftClick()), f, !on, () -> {
                    if (p.getOpenInventory().getTopInventory().getHolder(false) == this) {
                        refresh(p);
                    }
                });
            });
        }
        set(22, Icons.icon(Material.FILLED_MAP, lang.get("flags.back")), (p, c) -> new ClaimMapMenu(plugin, p, false).open(p));
        fill(Icons.filler());
    }
}
