package us.rapidskies.essentials.tools;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import us.rapidskies.essentials.RapidEssentials;
import us.rapidskies.essentials.command.PlayerCommand;
import us.rapidskies.essentials.command.Suggest;

/**
 * Staff commands that make or change items: {@code /give}, {@code /i}, {@code /more},
 * {@code /repair} and {@code /clearinventory}. On a survival server these put value into
 * the economy, so they're admin-only there (infra/luckperms/bootstrap.txt), and every use is
 * logged to {@code staff_actions}. Nothing is ever dropped on the ground: what doesn't fit
 * isn't given.
 */
public final class ItemCommands {

    public static final String GIVE = "rapid.command.give";
    public static final String MORE = "rapid.command.more";
    public static final String REPAIR = "rapid.command.repair";
    public static final String REPAIR_ALL = REPAIR + ".all";
    public static final String CLEAR = "rapid.command.clearinventory";
    public static final String CLEAR_OTHERS = CLEAR + ".others";
    /** At most an inventory's worth of stacks in one go. */
    public static final int MAX_AMOUNT = 36 * 64;

    private final RapidEssentials plugin;
    private List<String> itemIds;

    public ItemCommands(RapidEssentials plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ /give, /i

    /** {@code /give <player> <item> [amount]}; the item in vanilla's syntax, components included. */
    public BasicCommand give() {
        return new BasicCommand() {
            @Override
            public void execute(CommandSourceStack stack, String[] args) {
                CommandSender sender = stack.getSender();
                ItemInput input = args.length < 2 ? null : ItemInput.parse(Arrays.asList(args).subList(1, args.length));
                if (input == null) {
                    plugin.lang().send(sender, "give.usage");
                    return;
                }
                Player target = Target.resolve(plugin, sender, args, 0, GIVE);
                if (target != null) {
                    deliver(sender, target, input);
                }
            }

            @Override
            public Collection<String> suggest(CommandSourceStack stack, String[] args) {
                return switch (Suggest.position(args)) {
                    case 0 -> Suggest.players(plugin, stack.getSender(), args);
                    case 1 -> items(args);
                    default -> List.of();
                };
            }

            @Override
            public String permission() {
                return GIVE;
            }
        };
    }

    /** {@code /i <item> [amount]}: {@code /give} to yourself. */
    public BasicCommand item() {
        return new PlayerCommand(plugin, GIVE) {
            @Override
            protected void run(Player player, String[] args) {
                ItemInput input = ItemInput.parse(Arrays.asList(args));
                if (input == null) {
                    plugin.lang().send(player, "give.usage-self");
                    return;
                }
                deliver(player, player, input);
            }

            @Override
            protected Collection<String> complete(Player player, String[] args) {
                return Suggest.position(args) == 0 ? items(args) : List.of();
            }
        };
    }

    private void deliver(CommandSender sender, Player target, ItemInput input) {
        if (Target.busy(plugin, sender, target)) {
            return;
        }
        ItemStack item;
        try {
            item = Bukkit.getItemFactory().createItemStack(input.normalized());
        } catch (RuntimeException e) {
            item = null;
        }
        if (item == null || item.isEmpty()) {
            plugin.lang().send(sender, "give.unknown", Placeholder.unparsed("input", input.item()));
            return;
        }
        int amount = input.amount() == null ? item.getMaxStackSize() : input.amount();
        if (amount < 1 || amount > MAX_AMOUNT) {
            plugin.lang().send(sender, "give.bad-amount", Placeholder.unparsed("max", Integer.toString(MAX_AMOUNT)));
            return;
        }
        int left = add(target.getInventory(), item, amount);
        int given = amount - left;
        if (given == 0) {
            plugin.lang().send(sender, "give.no-room", Placeholder.unparsed("player", target.getName()));
            return;
        }
        Target.report(plugin, sender, target, "give.self", "give.other", "give.by-staff",
                Placeholder.unparsed("amount", Integer.toString(given)), Placeholder.component("item", item.displayName()));
        if (left > 0) {
            plugin.lang().send(sender, "give.partial", Placeholder.unparsed("left", Integer.toString(left)));
        }
        Target.log(plugin, sender, "give", target, given + " " + input.normalized());
    }

    /** Adds {@code amount} of {@code item} in full stacks; returns how many didn't fit. */
    private static int add(PlayerInventory inventory, ItemStack item, int amount) {
        int max = Math.max(1, item.getMaxStackSize());
        List<ItemStack> stacks = new ArrayList<>();
        for (int remaining = amount; remaining > 0; remaining -= max) {
            stacks.add(item.asQuantity(Math.min(max, remaining)));
        }
        Map<Integer, ItemStack> left = inventory.addItem(stacks.toArray(ItemStack[]::new));
        return left.values().stream().mapToInt(ItemStack::getAmount).sum();
    }

    /** Item ids starting with what's typed (only once something is typed: there are over a thousand). */
    private List<String> items(String[] args) {
        if (args.length == 0 || args[args.length - 1].isEmpty()) {
            return List.of();
        }
        if (itemIds == null) {
            itemIds = Arrays.stream(Material.values())
                    .filter(m -> !m.isLegacy() && m.isItem() && !m.isAir())
                    .map(m -> m.getKey().getKey())
                    .sorted()
                    .toList();
        }
        String typed = args[args.length - 1].toLowerCase(Locale.ROOT).replaceFirst("^minecraft:", "");
        return itemIds.stream().filter(id -> id.startsWith(typed)).limit(100).toList();
    }

    // ------------------------------------------------------------------ /more

    /** {@code /more}: the held stack, filled up to a full stack. */
    public BasicCommand more() {
        return new PlayerCommand(plugin, MORE) {
            @Override
            protected void run(Player player, String[] args) {
                if (Target.busy(plugin, player, player)) {
                    return;
                }
                PlayerInventory inv = player.getInventory();
                ItemStack hand = inv.getItemInMainHand();
                if (hand.isEmpty()) {
                    plugin.lang().send(player, "more.empty");
                    return;
                }
                int max = hand.getMaxStackSize();
                int added = max - hand.getAmount();
                if (added <= 0) {
                    plugin.lang().send(player, "more.full");
                    return;
                }
                inv.setItemInMainHand(hand.asQuantity(max));
                plugin.lang().send(player, "more.done", Placeholder.unparsed("amount", Integer.toString(max)));
                plugin.staffLog().log(player, "more", null, "+" + added + " " + hand.getType().getKey().asString());
            }
        };
    }

    // ------------------------------------------------------------------ /repair

    /** {@code /repair [hand|all]}: the held item, or (with {@code .all}) everything worn and carried. */
    public BasicCommand repair() {
        return new PlayerCommand(plugin, REPAIR) {
            @Override
            protected void run(Player player, String[] args) {
                String what = args.length == 0 ? "hand" : args[0].toLowerCase(Locale.ROOT);
                if (args.length > 1 || !(what.equals("hand") || what.equals("all"))) {
                    plugin.lang().send(player, "repair.usage");
                    return;
                }
                if (what.equals("all") && !player.hasPermission(REPAIR_ALL)) {
                    plugin.lang().send(player, "repair.no-all");
                    return;
                }
                if (Target.busy(plugin, player, player)) {
                    return;
                }
                PlayerInventory inv = player.getInventory();
                if (what.equals("hand")) {
                    ItemStack hand = inv.getItemInMainHand();
                    if (!repair(hand)) {
                        plugin.lang().send(player, "repair.nothing-hand");
                        return;
                    }
                    inv.setItemInMainHand(hand);
                    plugin.lang().send(player, "repair.hand");
                    plugin.staffLog().log(player, "repair", null, "hand " + hand.getType().getKey().asString());
                    return;
                }
                ItemStack[] contents = inv.getContents();
                int count = 0;
                for (ItemStack item : contents) {
                    if (repair(item)) {
                        count++;
                    }
                }
                if (count == 0) {
                    plugin.lang().send(player, "repair.nothing-all");
                    return;
                }
                inv.setContents(contents);
                plugin.lang().send(player, "repair.all", Placeholder.unparsed("count", Integer.toString(count)));
                plugin.staffLog().log(player, "repair", null, "all " + count);
            }

            @Override
            protected Collection<String> complete(Player player, String[] args) {
                if (Suggest.position(args) != 0) {
                    return List.of();
                }
                return Suggest.matching(player.hasPermission(REPAIR_ALL) ? List.of("hand", "all") : List.of("hand"),
                        args);
            }
        };
    }

    /** Takes all damage off {@code item}; returns whether it had any. */
    static boolean repair(ItemStack item) {
        if (item == null || item.isEmpty() || !(item.getItemMeta() instanceof Damageable meta) || !meta.hasDamage()) {
            return false;
        }
        meta.setDamage(0);
        item.setItemMeta(meta);
        return true;
    }

    // ------------------------------------------------------------------ /clearinventory

    /** {@code /clearinventory [player]}: everything carried and worn, gone. */
    public BasicCommand clearInventory() {
        return new TargetCommand(plugin, "clearinventory", CLEAR, CLEAR_OTHERS) {
            @Override
            protected void run(CommandSender sender, Player target) {
                if (Target.busy(plugin, sender, target)) {
                    return;
                }
                PlayerInventory inv = target.getInventory();
                long stacks = Arrays.stream(inv.getContents()).filter(i -> i != null && !i.isEmpty()).count();
                if (stacks == 0) {
                    plugin.lang().send(sender, "clear.empty");
                    return;
                }
                inv.clear();
                Target.report(plugin, sender, target, "clear.self", "clear.other", "clear.by-staff",
                        Placeholder.unparsed("count", Long.toString(stacks)));
                Target.log(plugin, sender, "clear-inventory", target, stacks + " stacks");
            }
        };
    }
}
