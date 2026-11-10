package us.rapidskies.claims.town;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import us.rapidskies.claims.RapidClaims;
import us.rapidskies.claims.data.ClaimRepository.ProjectRow;
import us.rapidskies.claims.model.Town;
import us.rapidskies.claims.model.TownPermission;
import us.rapidskies.common.lang.Lang;
import us.rapidskies.core.economy.ModeEconomy;

/**
 * Town Projects (PLAN.md §5.2): a town works on one Project at a time; members donate the
 * items and Crowns it needs, and when every requirement is met it completes, raising the
 * town's level and unlocking what it promises (warp, vault, extra chunks, ...).
 *
 * <p>Donated Crowns leave the economy (an economy sink, PLAN.md §5.2). Items are taken
 * from the donor's inventory first and given back if the database write fails, so nothing
 * is ever lost. Only plain items count (no names or enchantments), so nobody donates their
 * named sword by accident.
 */
public final class ProjectService {

    /** A project's progress in one town. {@code totals}: resource to amount given. */
    public record Progress(String project, boolean completed, Map<String, Long> totals, Map<UUID, Long> contributors) {

        public long given(String resource) {
            return totals.getOrDefault(resource, 0L);
        }

        public long remaining(Project p, String resource) {
            return Math.max(0, p.requires().getOrDefault(resource, 0L) - given(resource));
        }

        public boolean done(Project p) {
            return p.requires().keySet().stream().allMatch(r -> remaining(p, r) == 0);
        }
    }

    private final RapidClaims plugin;
    private final Lang lang;
    /** Per town, loaded on first use and kept in step with every donation made here. */
    private final Map<UUID, Map<String, Progress>> cache = new ConcurrentHashMap<>();
    private final Set<UUID> donating = ConcurrentHashMap.newKeySet();

    public ProjectService(RapidClaims plugin) {
        this.plugin = plugin;
        this.lang = plugin.lang();
    }

    /** The town's projects, loading them first if needed; {@code then} runs on the main thread. */
    public void progress(Town town, Consumer<Map<String, Progress>> then, Player who) {
        Map<String, Progress> known = cache.get(town.id());
        if (known != null) {
            then.accept(known);
            return;
        }
        plugin.async(() -> plugin.repo().projects(town.id()), rows -> {
            Map<String, Progress> map = new ConcurrentHashMap<>();
            for (ProjectRow r : rows) {
                map.put(r.project(), new Progress(r.project(), r.completed(), new HashMap<>(r.totals()),
                        new HashMap<>(r.contributors())));
            }
            cache.put(town.id(), map);
            then.accept(map);
        }, who);
    }

    /** Forgets a town's cached progress (another server changed it, or the town is gone). */
    public void invalidate(UUID town) {
        cache.remove(town);
    }

    public static Optional<String> active(Map<String, Progress> progress) {
        return progress.values().stream().filter(p -> !p.completed()).map(Progress::project).findFirst();
    }

    public void start(Player p, Town town, Project project) {
        if (!town.may(p.getUniqueId(), TownPermission.PROJECTS)) {
            lang.send(p, "town.no-permission", Placeholder.unparsed("permission", TownPermission.PROJECTS.id()));
            return;
        }
        progress(town, map -> {
            if (map.containsKey(project.id())) {
                lang.send(p, map.get(project.id()).completed() ? "project.already-done" : "project.already-active");
                return;
            }
            if (active(map).isPresent()) {
                lang.send(p, "project.one-at-a-time");
                return;
            }
            plugin.async(() -> plugin.repo().startProject(town.id(), project.id()), ok -> {
                if (!ok) {
                    invalidate(town.id());
                    lang.send(p, "project.already-active");
                    return;
                }
                map.put(project.id(), new Progress(project.id(), false, new HashMap<>(), new HashMap<>()));
                plugin.towns().broadcast(town, "project.started", name(project), Placeholder.unparsed("player", p.getName()));
            }, p);
        }, p);
    }

    /** Donates every plain {@code resource} item the player carries, up to what's still needed. */
    public void donateItems(Player p, Town town, Project project, String resource, Runnable after) {
        Material material = Material.matchMaterial(resource);
        if (material == null || !material.isItem()) {
            return;
        }
        withActive(p, town, project, progress -> {
            long need = progress.remaining(project, resource);
            if (need <= 0) {
                lang.send(p, "project.enough");
                return;
            }
            ItemStack plain = ItemStack.of(material);
            int carried = 0;
            for (ItemStack s : p.getInventory().getStorageContents()) {
                if (s != null && s.isSimilar(plain)) {
                    carried += s.getAmount();
                }
            }
            int give = (int) Math.min(carried, need);
            if (give <= 0) {
                lang.send(p, "project.none-carried", item(material));
                return;
            }
            if (!donating.add(p.getUniqueId())) {
                return;
            }
            int left = give;
            List<ItemStack> taken = new ArrayList<>();
            while (left > 0) {
                int stack = Math.min(left, material.getMaxStackSize());
                ItemStack chunk = ItemStack.of(material, stack);
                Map<Integer, ItemStack> missing = p.getInventory().removeItem(chunk.clone());
                int removed = stack - missing.values().stream().mapToInt(ItemStack::getAmount).sum();
                if (removed > 0) {
                    taken.add(ItemStack.of(material, removed));
                }
                left -= stack;
                if (!missing.isEmpty()) {
                    break;
                }
            }
            int amount = taken.stream().mapToInt(ItemStack::getAmount).sum();
            if (amount == 0) {
                donating.remove(p.getUniqueId());
                return;
            }
            record(p, town, project, progress, resource, amount, () -> {
                lang.send(p, "project.donated", Placeholder.unparsed("amount", String.format("%,d", amount)), item(material),
                        name(project));
                after.run();
            }, () -> {
                // The write failed: everything goes back, and nothing is ever dropped while it fits.
                for (ItemStack s : taken) {
                    p.getInventory().addItem(s).values().forEach(over -> p.getWorld().dropItemNaturally(p.getLocation(), over));
                }
            });
        });
    }

    public void donateCrowns(Player p, Town town, Project project, long amount, Runnable after) {
        ModeEconomy crowns = plugin.crowns();
        if (crowns == null) {
            lang.send(p, "errors.no-crowns");
            return;
        }
        withActive(p, town, project, progress -> {
            long give = Math.min(amount, progress.remaining(project, Project.CROWNS));
            if (give <= 0) {
                lang.send(p, "project.enough");
                return;
            }
            if (!donating.add(p.getUniqueId())) {
                return;
            }
            UUID id = p.getUniqueId();
            plugin.async(() -> {
                ModeEconomy.Change paid = crowns.withdraw(id, give, "town-project:" + project.id());
                if (!paid.success()) {
                    return false;
                }
                try {
                    plugin.repo().contribute(town.id(), project.id(), id, Project.CROWNS, give);
                } catch (SQLException e) {
                    crowns.deposit(id, give, "town-project-refund:" + project.id());
                    throw e;
                }
                return true;
            }, ok -> {
                donating.remove(id);
                if (!ok) {
                    lang.send(p, "project.too-poor");
                    return;
                }
                added(town, progress, Project.CROWNS, id, give);
                lang.send(p, "project.donated-crowns", Placeholder.unparsed("amount", String.format("%,d", give)),
                        name(project));
                completeIfDone(town, project, progress);
                after.run();
            }, () -> donating.remove(id), p);
        });
    }

    private void withActive(Player p, Town town, Project project, Consumer<Progress> then) {
        if (!town.isMember(p.getUniqueId())) {
            lang.send(p, "town.not-member");
            return;
        }
        progress(town, map -> {
            Progress progress = map.get(project.id());
            if (progress == null || progress.completed()) {
                lang.send(p, "project.not-active");
                return;
            }
            then.accept(progress);
        }, p);
    }

    private void record(Player p, Town town, Project project, Progress progress, String resource, int amount,
                        Runnable ok, Runnable undo) {
        UUID id = p.getUniqueId();
        plugin.async(() -> {
            plugin.repo().contribute(town.id(), project.id(), id, resource, amount);
            return null;
        }, done -> {
            donating.remove(id);
            added(town, progress, resource, id, amount);
            ok.run();
            completeIfDone(town, project, progress);
        }, () -> {
            donating.remove(id);
            undo.run();
        }, p);
    }

    private void added(Town town, Progress progress, String resource, UUID player, long amount) {
        Map<String, Progress> map = cache.get(town.id());
        if (map == null) {
            return;
        }
        Map<String, Long> totals = new HashMap<>(progress.totals());
        totals.merge(resource, amount, Long::sum);
        Map<UUID, Long> people = new HashMap<>(progress.contributors());
        people.merge(player, amount, Long::sum);
        map.put(progress.project(), new Progress(progress.project(), false, totals, people));
    }

    private void completeIfDone(Town town, Project project, Progress before) {
        Map<String, Progress> map = cache.get(town.id());
        Progress now = map == null ? before : map.get(project.id());
        if (now == null || !now.done(project)) {
            return;
        }
        plugin.async(() -> plugin.repo().completeProject(town.id(), project.id()), first -> {
            if (!first) {
                return;
            }
            if (map != null) {
                map.put(project.id(), new Progress(project.id(), true, now.totals(), now.contributors()));
            }
            plugin.land().town(town.id()).ifPresent(current -> {
                Set<String> done = new HashSet<>(current.completed());
                done.add(project.id());
                Town next = current.withCompleted(done);
                plugin.land().putTown(next);
                plugin.sync().town(next.id());
                List<String> unlocks = project.unlocks().stream().map(u -> lang.raw("project.unlock." + u.id())
                        .replace("<amount>", Integer.toString(u.amount()))).toList();
                plugin.towns().broadcast(next, "project.completed", name(project),
                        Placeholder.unparsed("level", Integer.toString(plugin.towns().level(next))),
                        Placeholder.parsed("unlocks", String.join("<dark>, </dark>", unlocks)));
            });
        }, null);
    }

    public static TagResolver name(Project p) {
        return Placeholder.component("project", Lang.parse(p.name()));
    }

    public static TagResolver item(Material m) {
        return Placeholder.component("item", Component.translatable(m.translationKey()));
    }
}
