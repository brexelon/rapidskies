package us.rapidskies.claims.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import us.rapidskies.claims.ClaimService;
import us.rapidskies.claims.LandService;
import us.rapidskies.claims.RapidClaims;
import us.rapidskies.claims.model.ChunkKey;
import us.rapidskies.claims.model.Claim;
import us.rapidskies.claims.model.LandIndex;
import us.rapidskies.claims.model.Owner;
import us.rapidskies.claims.model.Plot;
import us.rapidskies.claims.model.Role;
import us.rapidskies.claims.model.Town;
import us.rapidskies.claims.model.TownPermission;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.core.gui.Icons;
import us.rapidskies.core.gui.Menu;

/**
 * {@code /claim map} (PLAN.md §4.1, claiming UX): the chunks around the player as a 9×5
 * grid of glass panes, north up, the player's chunk in the middle. Left-click wilderness to
 * claim it, right-click your own land to give it up, no typing needed. The bottom row pans
 * the map, shows the allowance, switches between claiming for yourself and for your town,
 * shows borders in the world and opens the flags for the chunk you stand in.
 *
 * <pre>
 *   rows 0–4  chunk grid (dx −4..4, dz −2..2 around the centre)
 *   row 5     [◀][▲][▼][▶][allowance][mode][borders][flags][close]
 * </pre>
 *
 * Pane colors only help: every pane's name and lore say whose land it is.
 */
public final class ClaimMapMenu extends Menu {

    private static final int COLS = 9;
    private static final int ROWS = 5;

    private final RapidClaims plugin;
    private final LandIndex land;
    private final Lang lang;
    private ChunkKey center;
    private boolean townMode;

    public ClaimMapMenu(RapidClaims plugin, Player viewer, boolean townMode) {
        super(6, plugin.lang().get("map.title"));
        this.plugin = plugin;
        this.land = plugin.land();
        this.lang = plugin.lang();
        this.center = ClaimService.chunk(viewer.getLocation());
        this.townMode = townMode && canClaimForTown(viewer);
    }

    private boolean canClaimForTown(Player p) {
        return land.townOf(p.getUniqueId()).map(t -> t.may(p.getUniqueId(), TownPermission.CLAIM)).orElse(false);
    }

    @Override
    protected void render(Player viewer) {
        ChunkKey here = ClaimService.chunk(viewer.getLocation());
        boolean claimable = plugin.settings().worlds().contains(center.world());
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                ChunkKey chunk = center.offset(col - 4, row - 2);
                set(slot(row, col), cell(viewer, chunk, chunk.equals(here), claimable),
                        claimable ? (p, click) -> clicked(p, chunk, click) : null);
            }
        }
        set(slot(5, 0), Icons.icon(Material.ARROW, lang.get("map.pan-west")), (p, c) -> pan(p, -4, 0));
        set(slot(5, 1), Icons.icon(Material.ARROW, lang.get("map.pan-north")), (p, c) -> pan(p, 0, -2));
        set(slot(5, 2), Icons.icon(Material.ARROW, lang.get("map.pan-south")), (p, c) -> pan(p, 0, 2));
        set(slot(5, 3), Icons.icon(Material.ARROW, lang.get("map.pan-east")), (p, c) -> pan(p, 4, 0));
        set(slot(5, 4), infoIcon(viewer), (p, c) -> {
            center = ClaimService.chunk(p.getLocation());
            refresh(p);
        });
        if (canClaimForTown(viewer)) {
            set(slot(5, 5), Icons.icon(townMode ? Material.BELL : Material.PLAYER_HEAD,
                    lang.get(townMode ? "map.mode-town" : "map.mode-personal"), lang.list("map.mode-lore")), (p, c) -> {
                        townMode = !townMode;
                        refresh(p);
                    });
        }
        set(slot(5, 6), Icons.icon(Material.SPYGLASS, lang.get("map.borders"), lang.list("map.borders-lore")),
                (p, c) -> {
                    p.closeInventory();
                    plugin.borders().show(p);
                    lang.send(p, "show.legend");
                });
        set(slot(5, 7), Icons.icon(Material.COMPARATOR, lang.get("map.flags"), lang.list("map.flags-lore")),
                (p, c) -> {
                    ChunkKey at = ClaimService.chunk(p.getLocation());
                    if (plugin.lands().canManage(p, at)) {
                        new FlagsMenu(plugin, at).open(p);
                    } else {
                        lang.send(p, "flags.not-allowed");
                    }
                });
        set(slot(5, 8), Icons.icon(Material.BARRIER, lang.get("menu.close")), (p, c) -> p.closeInventory());
        fill(Icons.filler());
    }

    private ItemStack infoIcon(Player viewer) {
        LandService.Allowance a = plugin.lands().allowance(viewer);
        List<Component> lore = new ArrayList<>(lang.list("map.info-lore",
                Placeholder.unparsed("used", Integer.toString(a.used())),
                Placeholder.unparsed("total", Integer.toString(a.total())),
                Placeholder.unparsed("left", Integer.toString(a.left()))));
        land.townOf(viewer.getUniqueId()).ifPresent(t -> lore.add(lang.get("map.info-town",
                Placeholder.unparsed("count", Integer.toString(land.countOf(Owner.town(t.id())))),
                Placeholder.unparsed("cap", Integer.toString(plugin.towns().chunkCap(t))))));
        lore.add(lang.get("map.info-recenter"));
        return Icons.icon(Material.FILLED_MAP, lang.get("map.info-name",
                Placeholder.unparsed("x", Integer.toString(center.x())),
                Placeholder.unparsed("z", Integer.toString(center.z()))), lore);
    }

    private ItemStack cell(Player viewer, ChunkKey chunk, boolean here, boolean claimable) {
        List<Component> lore = new ArrayList<>();
        lore.add(lang.get("map.cell-coords", Placeholder.unparsed("x", Integer.toString(chunk.x())),
                Placeholder.unparsed("z", Integer.toString(chunk.z()))));
        if (here) {
            lore.add(lang.get("map.cell-here"));
        }
        Material pane;
        Component name;
        Optional<Claim> claim = land.claim(chunk);
        if (!claimable) {
            pane = Material.GRAY_STAINED_GLASS_PANE;
            name = lang.get("map.cell-unclaimable");
        } else if (claim.isEmpty()) {
            pane = Material.WHITE_STAINED_GLASS_PANE;
            name = lang.get("map.cell-wilderness");
            lore.add(lang.get(townMode ? "map.click-claim-town" : "map.click-claim"));
        } else {
            Owner owner = claim.get().owner();
            Optional<Plot> plot = land.plot(chunk);
            Role role = land.role(viewer.getUniqueId(), chunk);
            boolean mine = owner.equals(Owner.player(viewer.getUniqueId()));
            boolean myTown = owner.isTown() && land.townOf(viewer.getUniqueId()).map(t -> t.id().equals(owner.id()))
                    .orElse(false);
            name = lang.get("map.cell-owned", Placeholder.component("owner", plugin.ownerName(owner)));
            plot.ifPresent(pl -> lore.add(lang.get("map.cell-plot",
                    Placeholder.unparsed("resident", plugin.names().name(pl.resident())))));
            if (ruined(owner)) {
                lore.add(lang.get("map.cell-ruins"));
            }
            if (mine) {
                pane = Material.LIME_STAINED_GLASS_PANE;
                lore.add(lang.get("map.click-unclaim"));
            } else if (myTown) {
                pane = Material.CYAN_STAINED_GLASS_PANE;
                if (townMode) {
                    lore.add(lang.get("map.click-unclaim-town"));
                } else if (plot.isEmpty() && townModeCanAbsorb(viewer, chunk)) {
                    lore.add(lang.get("map.switch-to-town"));
                }
            } else if (role.atLeast(Role.TRUSTED)) {
                pane = Material.YELLOW_STAINED_GLASS_PANE;
                lore.add(lang.get("map.cell-your-role", Placeholder.component("role", lang.get("role." + role.id()))));
            } else {
                pane = Material.RED_STAINED_GLASS_PANE;
            }
            if (ruined(owner)) {
                pane = Material.BROWN_STAINED_GLASS_PANE;
            }
        }
        ItemStack icon = Icons.icon(pane, name, lore);
        return here ? Icons.glow(icon) : icon;
    }

    private boolean townModeCanAbsorb(Player viewer, ChunkKey chunk) {
        return canClaimForTown(viewer) && !land.isClaimed(chunk);
    }

    private boolean ruined(Owner owner) {
        if (owner.isTown()) {
            return land.town(owner.id()).map(Town::ruined).orElse(false);
        }
        return plugin.lands().row(owner.id()).ruinsSince().isPresent();
    }

    private void clicked(Player p, ChunkKey chunk, ClickType click) {
        Optional<Claim> claim = land.claim(chunk);
        boolean right = click.isRightClick();
        if (townMode) {
            if (right) {
                plugin.towns().unclaim(p, chunk);
            } else {
                plugin.towns().claim(p, chunk);
            }
        } else if (right) {
            if (claim.isPresent() && claim.get().owner().equals(Owner.player(p.getUniqueId()))) {
                plugin.lands().unclaim(p, chunk);
            }
        } else if (claim.isEmpty()) {
            plugin.lands().claim(p, chunk);
        } else {
            return;
        }
        // The change lands after a database round trip; redraw once it has.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.getOpenInventory().getTopInventory().getHolder(false) == this) {
                refresh(p);
            }
        }, 10L);
    }

    private void pan(Player p, int dx, int dz) {
        center = center.offset(dx, dz);
        refresh(p);
    }
}
