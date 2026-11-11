package us.rapidskies.skyrealms.expedition;

import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * Makes an Expedition's heart easy to find: a glowing copy of its block, a little larger and
 * fully lit, whose outline shows through walls in the theme's colour; a label over it, also seen
 * through walls, with the strikes so far; and light rising from it. Display entities have no
 * hitbox, so strikes still land on the block.
 *
 * <p>The copy is an item display rather than a block display, since block displays don't draw
 * block entities such as the sunken ship's chest. Nothing is saved with the world, and the
 * heart's chunk is held loaded while the marker is up, so it can neither outlive the Expedition
 * nor vanish when everyone steps away; {@link #show} also respawns anything that went missing.
 */
final class HeartMarker {

    private static final float SHELL = 1.04f;

    private final Plugin plugin;
    private final World world;
    private final int x;
    private final int y;
    private final int z;
    private final Material material;
    private final Color glow;
    private ItemDisplay shell;
    private TextDisplay label;
    private boolean ticket;
    private double spin;

    HeartMarker(Plugin plugin, World world, int x, int y, int z, Material material, Color glow) {
        this.plugin = plugin;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.material = material;
        this.glow = glow;
    }

    /** Shows the marker with this label, spawning whatever is missing. Main thread. */
    void show(Component text) {
        if (!ticket) {
            world.addPluginChunkTicket(x >> 4, z >> 4, plugin);
            ticket = true;
        }
        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
            return;
        }
        if (shell == null || !shell.isValid()) {
            shell = world.spawn(world.getBlockAt(x, y, z).getLocation().add(0.5, 0.5, 0.5), ItemDisplay.class, d -> {
                d.setPersistent(false);
                d.setItemStack(ItemStack.of(material));
                d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                        new Vector3f(SHELL, SHELL, SHELL), new AxisAngle4f()));
                d.setBrightness(new Display.Brightness(15, 15));
                d.setGlowColorOverride(glow);
                d.setGlowing(true);
                d.setViewRange(1.5f);
            });
        }
        if (label == null || !label.isValid()) {
            label = world.spawn(world.getBlockAt(x, y, z).getLocation().add(0.5, 1.45, 0.5), TextDisplay.class, d -> {
                d.setPersistent(false);
                d.text(text);
                d.setBillboard(Display.Billboard.CENTER);
                d.setAlignment(TextDisplay.TextAlignment.CENTER);
                d.setSeeThrough(true);
                d.setShadowed(true);
                d.setDefaultBackground(false);
                d.setBackgroundColor(Color.fromARGB(90, 0, 0, 0));
                d.setBrightness(new Display.Brightness(15, 15));
                d.setViewRange(1.5f);
            });
        } else {
            label.text(text);
        }
    }

    /** Motes of light rising from the heart and a turning ring of its colour; once a second. */
    void sparkle() {
        if (shell == null || !shell.isValid()) {
            return;
        }
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < 3; i++) {
            world.spawnParticle(Particle.END_ROD, x + 0.5 + (rnd.nextDouble() - 0.5) * 0.9, y + 1.05,
                    z + 0.5 + (rnd.nextDouble() - 0.5) * 0.9, 0, 0, 1, 0, 0.05);
        }
        Particle.DustOptions dust = new Particle.DustOptions(glow, 1.2f);
        for (int i = 0; i < 12; i++) {
            double a = spin + Math.PI * 2 * i / 12;
            world.spawnParticle(Particle.DUST, x + 0.5 + Math.cos(a) * 0.95, y + 0.15, z + 0.5 + Math.sin(a) * 0.95,
                    1, 0, 0, 0, 0, dust);
        }
        spin += 0.35;
    }

    /** Takes it all away and lets the chunk go. */
    void remove() {
        if (shell != null) {
            shell.remove();
            shell = null;
        }
        if (label != null) {
            label.remove();
            label = null;
        }
        if (ticket) {
            world.removePluginChunkTicket(x >> 4, z >> 4, plugin);
            ticket = false;
        }
    }
}
