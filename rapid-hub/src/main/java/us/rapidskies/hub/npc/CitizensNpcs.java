package us.rapidskies.hub.npc;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.NPCClickEvent;
import net.citizensnpcs.api.event.NPCLeftClickEvent;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.trait.LookClose;
import net.citizensnpcs.trait.SkinTrait;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import us.rapidskies.hub.HubSettings.Mode;
import us.rapidskies.hub.RapidHub;

/**
 * One Citizens player NPC per gamemode on the stage; clicking it (either button) joins that
 * mode. They live in an in-memory registry, so Citizens never saves them: every start builds
 * them from the config, like the labels above their heads (Citizens' own nameplate is
 * hidden; the label shows the name and the live player count).
 */
public final class CitizensNpcs implements HubNpcs, Listener {

    private static final String REGISTRY = "rapid-hub";
    /** Label height above the NPC's feet: just over a player's head. */
    private static final double LABEL_HEIGHT = 2.05;
    private static final double LOOK_RANGE = 10;

    private record Spawned(Mode mode, NPC npc, TextDisplay label) {
    }

    private final RapidHub hub;
    private final Map<UUID, Spawned> byNpc = new HashMap<>();
    private NPCRegistry registry;

    public CitizensNpcs(RapidHub hub) {
        this.hub = hub;
    }

    @Override
    public void start() {
        CitizensAPI.removeNamedNPCRegistry(REGISTRY);
        registry = CitizensAPI.createInMemoryNPCRegistry(REGISTRY);
        hub.getServer().getPluginManager().registerEvents(this, hub);
        for (Mode mode : hub.settings().modes()) {
            if (mode.npc().isEmpty()) {
                continue;
            }
            Location at = hub.labelLocation(mode.npc().get(), "modes." + mode.id() + ".npc");
            if (at == null) {
                continue;
            }
            hub.labels().keepLoaded(at);
            // Player names are at most 16 characters; nobody sees this one.
            String name = "mode-" + mode.id();
            NPC npc = registry.createNPC(EntityType.PLAYER, name.length() > 16 ? name.substring(0, 16) : name);
            npc.data().set(NPC.Metadata.NAMEPLATE_VISIBLE, false);
            npc.data().set(NPC.Metadata.COLLIDABLE, false);
            npc.setProtected(true);
            SkinTrait skin = npc.getOrAddTrait(SkinTrait.class);
            if (mode.skin().fixed()) {
                skin.setSkinPersistent("rapid-" + mode.id(), mode.skin().signature(), mode.skin().texture());
            } else if (!mode.skin().name().isEmpty()) {
                skin.setSkinName(mode.skin().name());
            } else {
                // Otherwise Citizens would look up the skin of whoever is called "mode-smp".
                skin.setFetchDefaultSkin(false);
            }
            LookClose look = npc.getOrAddTrait(LookClose.class);
            look.lookClose(true);
            look.setRange(LOOK_RANGE);
            look.setRealisticLooking(true);
            npc.spawn(at);
            TextDisplay label = hub.labels().spawn(at.clone().add(0, LABEL_HEIGHT, 0), label(mode));
            byNpc.put(npc.getUniqueId(), new Spawned(mode, npc, label));
        }
        hub.getSLF4JLogger().info("Spawned {} mode NPC(s)", byNpc.size());
    }

    private Component label(Mode mode) {
        return hub.lang().get("npc.label", Placeholder.component("mode", hub.selector().name(mode)),
                Placeholder.component("status", hub.selector().status(mode)));
    }

    @Override
    public void updateLabels() {
        for (Spawned s : byNpc.values()) {
            if (s.label().isValid()) {
                s.label().text(label(s.mode()));
            }
        }
    }

    @Override
    public void stop() {
        HandlerList.unregisterAll(this);
        byNpc.clear();
        if (registry != null) {
            registry.deregisterAll();
            CitizensAPI.removeNamedNPCRegistry(REGISTRY);
            registry = null;
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onRightClick(NPCRightClickEvent event) {
        click(event);
    }

    @EventHandler(ignoreCancelled = true)
    public void onLeftClick(NPCLeftClickEvent event) {
        click(event);
    }

    private void click(NPCClickEvent event) {
        Spawned s = byNpc.get(event.getNPC().getUniqueId());
        if (s != null) {
            event.setCancelled(true);
            hub.selector().join(event.getClicker(), s.mode());
        }
    }
}
