package us.rapidskies.horizons.npc;

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
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import us.rapidskies.core.display.Labels;
import us.rapidskies.horizons.HorizonsSettings.Npc;
import us.rapidskies.horizons.RapidHorizons;
import us.rapidskies.horizons.trade.TradesMenu;

/**
 * Citizens player NPCs around the spawn plaza ({@code npcs} in the config): the Heartland
 * guide, the Trade master, the land surveyor and the way back to the lobby. Clicking one
 * (either button) does its action. Like the hub's mode NPCs they live in an in-memory
 * registry, so Citizens never saves them: every start builds them from the config, with a
 * text-display label over each head (Citizens' own nameplate is hidden).
 */
public final class CitizensSpawnNpcs implements SpawnNpcs, Listener {

    private static final String REGISTRY = "rapid-horizons";
    /** Label height above the NPC's feet: just over a player's head. */
    private static final double LABEL_HEIGHT = 2.05;
    private static final double LOOK_RANGE = 10;
    /** Citizens can report one click twice (both hands); one action per second is plenty. */
    private static final long CLICK_COOLDOWN_MILLIS = 1_000;

    private final RapidHorizons plugin;
    private final Labels labels;
    private final Map<UUID, Npc> byNpc = new HashMap<>();
    private final Map<UUID, Long> lastClick = new HashMap<>();
    private NPCRegistry registry;

    public CitizensSpawnNpcs(RapidHorizons plugin) {
        this.plugin = plugin;
        this.labels = new Labels(plugin);
    }

    @Override
    public void start() {
        CitizensAPI.removeNamedNPCRegistry(REGISTRY);
        registry = CitizensAPI.createInMemoryNPCRegistry(REGISTRY);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        for (Npc config : plugin.settings().npcs()) {
            if (config.location().isEmpty()) {
                continue;
            }
            Location at = Labels.location(config.location().get());
            if (at == null) {
                plugin.getSLF4JLogger().error("npcs.{}.location: world '{}' isn't loaded", config.id(),
                        config.location().get().world());
                continue;
            }
            labels.keepLoaded(at);
            // Player names are at most 16 characters; nobody sees this one.
            String name = "hz-" + config.id();
            NPC npc = registry.createNPC(EntityType.PLAYER, name.length() > 16 ? name.substring(0, 16) : name);
            npc.data().set(NPC.Metadata.NAMEPLATE_VISIBLE, false);
            npc.data().set(NPC.Metadata.COLLIDABLE, false);
            npc.setProtected(true);
            SkinTrait skin = npc.getOrAddTrait(SkinTrait.class);
            if (config.skin().fixed()) {
                skin.setSkinPersistent("rapid-hz-" + config.id(), config.skin().signature(), config.skin().texture());
            } else if (!config.skin().name().isEmpty()) {
                skin.setSkinName(config.skin().name());
            } else {
                // Otherwise Citizens would look up the skin of whoever is called "hz-trades".
                skin.setFetchDefaultSkin(false);
            }
            LookClose look = npc.getOrAddTrait(LookClose.class);
            look.lookClose(true);
            look.setRange(LOOK_RANGE);
            look.setRealisticLooking(true);
            npc.spawn(at);
            labels.spawn(at.clone().add(0, LABEL_HEIGHT, 0), label(config));
            byNpc.put(npc.getUniqueId(), config);
        }
        plugin.getSLF4JLogger().info("Spawned {} spawn NPC(s)", byNpc.size());
    }

    private Component label(Npc config) {
        return plugin.lang().get("npc.label",
                Placeholder.component("name", plugin.lang().get("npc." + config.id() + ".name")),
                Placeholder.component("hint", plugin.lang().get("npc." + config.id() + ".hint")));
    }

    @Override
    public void stop() {
        HandlerList.unregisterAll(this);
        byNpc.clear();
        lastClick.clear();
        if (registry != null) {
            registry.deregisterAll();
            CitizensAPI.removeNamedNPCRegistry(REGISTRY);
            registry = null;
        }
        labels.removeAll();
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
        Npc config = byNpc.get(event.getNPC().getUniqueId());
        if (config == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getClicker();
        long now = System.currentTimeMillis();
        Long last = lastClick.get(player.getUniqueId());
        if (last != null && now - last < CLICK_COOLDOWN_MILLIS) {
            return;
        }
        lastClick.put(player.getUniqueId(), now);
        switch (config.action().kind()) {
            // Like the portal beside it: no /rtp cooldown, the spawn is where new players start.
            case RTP -> plugin.rtp().start(player, true);
            case TRADES -> new TradesMenu(plugin).open(player);
            case SERVER -> {
                // Leaving mid-fight is combat logging (CombatLog): refuse instead of killing them.
                if (plugin.pvp().inCombat(player.getUniqueId())) {
                    plugin.lang().send(player, "pvp.no-leave");
                } else {
                    plugin.core().connect(player, config.action().value());
                }
            }
            case COMMAND -> player.performCommand(config.action().value());
        }
    }
}
