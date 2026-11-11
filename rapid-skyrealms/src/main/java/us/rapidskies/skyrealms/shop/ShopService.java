package us.rapidskies.skyrealms.shop;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import us.rapidskies.common.economy.EconomyMode;
import us.rapidskies.core.economy.ModeEconomy;
import us.rapidskies.skyrealms.Give;
import us.rapidskies.skyrealms.RapidSkyRealms;
import us.rapidskies.skyrealms.SkyRealmsSettings;
import us.rapidskies.skyrealms.SkyRealmsSettings.Category;
import us.rapidskies.skyrealms.SkyRealmsSettings.ShopItem;
import us.rapidskies.skyrealms.SkyRealmsSettings.Tier;
import us.rapidskies.skyrealms.tier.TierGoal;

/**
 * The SkyRealms server shop (PLAN.md §5.1: Coins are earned by selling to it): buy basics,
 * sell what the island makes. Categories open with Realm Tiers. Only plain items sell.
 *
 * <p>A sale takes the items from the inventory first, then pays in one database transaction
 * with the day's running total (the soft cap); if paying fails the items go back. A purchase
 * charges first (the ledger's guarded debit) and then hands the items over; it only sells what
 * fits. One shop action per player at a time.
 */
public final class ShopService {

    private final RapidSkyRealms plugin;
    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();
    /** Full-price worth sold today, for the menus (refreshed on open and after sales). */
    private final Map<UUID, Long> soldToday = new ConcurrentHashMap<>();

    public ShopService(RapidSkyRealms plugin) {
        this.plugin = plugin;
    }

    private SkyRealmsSettings.Shop cfg() {
        return plugin.settings().shop();
    }

    public boolean unlocked(Player player, Category category) {
        Tier tier = plugin.tiers().tierOfPlayer(player);
        return plugin.tiers().unlocked(category.tier(), tier);
    }

    public long soldToday(Player player) {
        return soldToday.getOrDefault(player.getUniqueId(), 0L);
    }

    /** Re-reads today's sales (for the menu's rate line), then runs {@code then}. */
    public void refreshSold(Player player, Runnable then) {
        LocalDate day = LocalDate.now(ZoneOffset.UTC);
        plugin.async(() -> plugin.repo().earnedToday(player.getUniqueId(), day), n -> {
            soldToday.put(player.getUniqueId(), n);
            then.run();
        });
    }

    public void forget(UUID player) {
        soldToday.remove(player);
        busy.remove(player);
    }

    // ------------------------------------------------------------------ buying

    public void buy(Player player, ShopItem item, int wanted) {
        ModeEconomy economy = plugin.core().economy();
        if (!item.buyable() || economy == null) {
            return;
        }
        int amount = Math.min(wanted, Give.room(player.getInventory(), item.material()));
        if (amount <= 0) {
            plugin.lang().send(player, "shop.full");
            return;
        }
        if (!busy.add(player.getUniqueId())) {
            return;
        }
        long cost = ShopPricing.cost(item.buy(), amount);
        UUID id = player.getUniqueId();
        plugin.async(() -> economy.withdraw(id, cost, "shop:buy"), change -> {
            busy.remove(id);
            if (!change.success()) {
                plugin.lang().send(player, "shop.cant-afford", Placeholder.unparsed("cost", coins(cost)),
                        Placeholder.unparsed("balance", coins(change.balance())));
                return;
            }
            Give.items(player, List.of(Give.stacks(item.material(), amount)));
            plugin.lang().send(player, "shop.bought", Placeholder.unparsed("amount", Integer.toString(amount)),
                    item(item.material()), Placeholder.unparsed("cost", coins(cost)),
                    Placeholder.unparsed("balance", coins(change.balance())));
        }, player, () -> busy.remove(id));
    }

    // ------------------------------------------------------------------ selling

    /** Sells the held stack, if it's plain and the shop takes it. */
    public void sellHand(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!Give.plain(hand) || sellable(player, hand.getType()).isEmpty()) {
            plugin.lang().send(player, "shop.not-sellable");
            return;
        }
        sell(player, m -> m == hand.getType(), Integer.MAX_VALUE, true);
    }

    /** Sells every plain item the shop takes (main inventory only). */
    public void sellAll(Player player) {
        sell(player, m -> true, Integer.MAX_VALUE, false);
    }

    /** Sells up to {@code limit} of one material. */
    public void sell(Player player, Material material, int limit) {
        sell(player, m -> m == material, limit, false);
    }

    private Optional<ShopItem> sellable(Player player, Material m) {
        return cfg().sellable(m, c -> unlocked(player, c));
    }

    private void sell(Player player, Predicate<Material> which, int limit, boolean handOnly) {
        if (plugin.core().economy() == null) {
            return;
        }
        if (!busy.add(player.getUniqueId())) {
            return;
        }
        // Take the goods first (main thread), remembering them to give back if paying fails.
        Map<Material, Integer> taken = new EnumMap<>(Material.class);
        double value = 0;
        if (handOnly) {
            ItemStack hand = player.getInventory().getItemInMainHand();
            Optional<ShopItem> price = sellable(player, hand.getType());
            if (price.isPresent() && Give.plain(hand)) {
                int n = Math.min(limit, hand.getAmount());
                taken.put(hand.getType(), n);
                value += n * price.get().sell();
                hand.setAmount(hand.getAmount() - n);
                player.getInventory().setItemInMainHand(hand.getAmount() <= 0 ? null : hand);
            }
        } else {
            Map<Material, Integer> carried = new EnumMap<>(Material.class);
            for (ItemStack s : player.getInventory().getStorageContents()) {
                if (Give.plain(s) && which.test(s.getType())) {
                    carried.merge(s.getType(), s.getAmount(), Integer::sum);
                }
            }
            for (Map.Entry<Material, Integer> e : carried.entrySet()) {
                Optional<ShopItem> price = sellable(player, e.getKey());
                if (price.isEmpty()) {
                    continue;
                }
                int n = Give.take(player.getInventory(), e.getKey(), Math.min(limit, e.getValue()));
                if (n > 0) {
                    taken.put(e.getKey(), n);
                    value += n * price.get().sell();
                }
            }
        }
        if (taken.isEmpty()) {
            busy.remove(player.getUniqueId());
            plugin.lang().send(player, "shop.nothing-to-sell");
            return;
        }
        UUID id = player.getUniqueId();
        double tailwind = plugin.core().tailwind().multiplier(player);
        double worth = value;
        String txn = "shop:" + id + ":" + UUID.randomUUID();
        LocalDate day = LocalDate.now(ZoneOffset.UTC);
        plugin.async(() -> plugin.repo().sell(plugin.core().modeLedger(EconomyMode.SKYREALMS), id, day, worth, cfg(),
                tailwind, plugin.core().settings().serverId(), txn), sale -> {
            busy.remove(id);
            plugin.core().economy().changed(id, sale.balance());
            soldToday.merge(id, Math.round(worth), Long::sum);
            List<Component> names = new ArrayList<>();
            taken.forEach((m, n) -> names.add(Component.text(n + "× ").append(Component.translatable(m.translationKey()))));
            plugin.lang().send(player, sale.ratePercent() < 100 ? "shop.sold-capped" : "shop.sold",
                    Placeholder.component("items", Component.join(JoinConfiguration.commas(true), names)),
                    Placeholder.unparsed("paid", coins(sale.paid())),
                    Placeholder.unparsed("balance", coins(sale.balance())),
                    Placeholder.unparsed("rate", Integer.toString(sale.ratePercent())));
            plugin.islands().of(player).ifPresent(i ->
                    plugin.tiers().record(i.id(), TierGoal.Type.SELL, null, sale.paid()));
            if (player.isOnline()) {
                // Quests and achievements (PLAN.md §7): Coins earned at the shop.
                plugin.core().quests().record(player, "shop-sell", sale.paid());
            }
        }, player, () -> {
            busy.remove(id);
            List<ItemStack> back = new ArrayList<>();
            taken.forEach((m, n) -> back.addAll(List.of(Give.stacks(m, n))));
            Give.items(player, back);
        });
    }

    static String coins(long n) {
        return String.format("%,d", n);
    }

    private static net.kyori.adventure.text.minimessage.tag.resolver.TagResolver item(Material m) {
        return Placeholder.component("item", Component.translatable(m.translationKey()));
    }
}
