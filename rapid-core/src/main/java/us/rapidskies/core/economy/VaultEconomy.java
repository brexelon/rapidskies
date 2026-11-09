package us.rapidskies.core.economy;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import us.rapidskies.common.economy.EconomyMode;

/**
 * The Vault economy for this server's gamemode currency (PLAN.md §4), registered with
 * VaultUnlocked so SuperiorSkyblock2 (SkyRealms) and QuickShop-Hikari (Horizons) charge and
 * pay in Coins or Crowns. This is the classic Vault API; VaultUnlocked serves it as is.
 * QuickShop must be set to its classic Vault economy, since nothing implements the newer
 * VaultUnlocked interface yet.
 *
 * <p>Amounts are whole units ({@link Amounts}). Every ledger row records which plugin made
 * the call ({@code vault:QuickShop-Hikari}), so economy audits can tell sources apart.
 * Banks aren't supported: islands and towns keep their own money. Only loaded when Vault
 * is installed, so its classes are never needed otherwise.
 */
// VaultUnlocked deprecates the classic API in favour of its own, but the classic one is what
// SuperiorSkyblock2 calls, so it is the one to provide.
@SuppressWarnings("deprecation")
public final class VaultEconomy implements Economy {

    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private final Plugin plugin;
    private final ModeEconomy economy;
    private final EconomyMode mode;

    private VaultEconomy(Plugin plugin, ModeEconomy economy) {
        this.plugin = plugin;
        this.economy = economy;
        this.mode = economy.mode();
    }

    public static void register(Plugin plugin, ModeEconomy economy) {
        Bukkit.getServicesManager().register(Economy.class, new VaultEconomy(plugin, economy), plugin,
                ServicePriority.Highest);
        plugin.getSLF4JLogger().info("Registered the {} Vault economy", economy.mode().currencyName(2));
    }

    public static void unregister(Plugin plugin) {
        Bukkit.getServicesManager().unregisterAll(plugin);
    }

    // ------------------------------------------------------------------ identity

    @Override
    public boolean isEnabled() {
        return plugin.isEnabled();
    }

    @Override
    public String getName() {
        return "rapid-core " + mode.currencyName(2);
    }

    @Override
    public boolean hasBankSupport() {
        return false;
    }

    @Override
    public int fractionalDigits() {
        return 0;
    }

    @Override
    public String format(double amount) {
        long whole = (long) Math.floor(amount);
        return String.format("%,d %s", whole, mode.currencyName(whole));
    }

    @Override
    public String currencyNamePlural() {
        return mode.currencyName(2);
    }

    @Override
    public String currencyNameSingular() {
        return mode.currencyName(1);
    }

    // ------------------------------------------------------------------ accounts (implicit)

    @Override
    public boolean hasAccount(OfflinePlayer player) {
        return true;
    }

    @Override
    public boolean hasAccount(OfflinePlayer player, String world) {
        return true;
    }

    @Override
    public boolean hasAccount(String name) {
        return resolve(name).isPresent();
    }

    @Override
    public boolean hasAccount(String name, String world) {
        return hasAccount(name);
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player) {
        return true;
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player, String world) {
        return true;
    }

    @Override
    public boolean createPlayerAccount(String name) {
        return resolve(name).isPresent();
    }

    @Override
    public boolean createPlayerAccount(String name, String world) {
        return createPlayerAccount(name);
    }

    // ------------------------------------------------------------------ balances

    @Override
    public double getBalance(OfflinePlayer player) {
        return balance(player.getUniqueId());
    }

    @Override
    public double getBalance(OfflinePlayer player, String world) {
        return getBalance(player);
    }

    @Override
    public double getBalance(String name) {
        return resolve(name).map(this::balance).orElse(0.0);
    }

    @Override
    public double getBalance(String name, String world) {
        return getBalance(name);
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        OptionalLong units = Amounts.forWithdrawal(amount);
        return units.isPresent() && balance(player.getUniqueId()) >= units.getAsLong();
    }

    @Override
    public boolean has(OfflinePlayer player, String world, double amount) {
        return has(player, amount);
    }

    @Override
    public boolean has(String name, double amount) {
        Optional<UUID> id = resolve(name);
        OptionalLong units = Amounts.forWithdrawal(amount);
        return id.isPresent() && units.isPresent() && balance(id.get()) >= units.getAsLong();
    }

    @Override
    public boolean has(String name, String world, double amount) {
        return has(name, amount);
    }

    private double balance(UUID player) {
        try {
            return economy.balance(player);
        } catch (SQLException e) {
            plugin.getSLF4JLogger().warn("Vault balance lookup failed for {}: {}", player, e.getMessage());
            return 0;
        }
    }

    // ------------------------------------------------------------------ changes

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        return withdraw(Optional.of(player.getUniqueId()), amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, String world, double amount) {
        return withdrawPlayer(player, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(String name, double amount) {
        return withdraw(resolve(name), amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(String name, String world, double amount) {
        return withdrawPlayer(name, amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        return deposit(Optional.of(player.getUniqueId()), amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, String world, double amount) {
        return depositPlayer(player, amount);
    }

    @Override
    public EconomyResponse depositPlayer(String name, double amount) {
        return deposit(resolve(name), amount);
    }

    @Override
    public EconomyResponse depositPlayer(String name, String world, double amount) {
        return depositPlayer(name, amount);
    }

    private EconomyResponse withdraw(Optional<UUID> player, double amount) {
        OptionalLong units = Amounts.forWithdrawal(amount);
        if (player.isEmpty() || units.isEmpty()) {
            return failure(amount, 0, player.isEmpty() ? "Unknown player" : "Invalid amount");
        }
        try {
            ModeEconomy.Change c = economy.withdraw(player.get(), units.getAsLong(), reason());
            return c.success()
                    ? new EconomyResponse(units.getAsLong(), c.balance(), EconomyResponse.ResponseType.SUCCESS, null)
                    : failure(amount, c.balance(), "Insufficient funds");
        } catch (SQLException | RuntimeException e) {
            plugin.getSLF4JLogger().error("Vault withdrawal of {} from {} failed", amount, player.get(), e);
            return failure(amount, 0, "Economy unavailable");
        }
    }

    private EconomyResponse deposit(Optional<UUID> player, double amount) {
        OptionalLong units = Amounts.forDeposit(amount);
        if (player.isEmpty() || units.isEmpty()) {
            return failure(amount, 0, player.isEmpty() ? "Unknown player" : "Invalid amount");
        }
        try {
            ModeEconomy.Change c = economy.deposit(player.get(), units.getAsLong(), reason());
            return c.success()
                    ? new EconomyResponse(units.getAsLong(), c.balance(), EconomyResponse.ResponseType.SUCCESS, null)
                    : failure(amount, c.balance(), "Deposit refused");
        } catch (SQLException | RuntimeException e) {
            plugin.getSLF4JLogger().error("Vault deposit of {} to {} failed", amount, player.get(), e);
            return failure(amount, 0, "Economy unavailable");
        }
    }

    private static EconomyResponse failure(double amount, double balance, String message) {
        return new EconomyResponse(amount, balance, EconomyResponse.ResponseType.FAILURE, message);
    }

    /** "vault:QuickShop-Hikari": the first plugin on the call stack that isn't rapid-core. */
    private String reason() {
        String caller = WALKER.walk(frames -> frames
                .map(StackWalker.StackFrame::getDeclaringClass)
                .filter(c -> c.getClassLoader() != VaultEconomy.class.getClassLoader())
                .map(VaultEconomy::owningPlugin)
                .flatMap(Optional::stream)
                .findFirst())
                .orElse("unknown");
        return "vault:" + caller;
    }

    private static Optional<String> owningPlugin(Class<?> type) {
        try {
            return Optional.of(JavaPlugin.getProvidingPlugin(type).getName());
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Optional.empty(); // a server or JDK class
        }
    }

    /** Legacy name-based calls: online players first, then anyone this server has seen. */
    private static Optional<UUID> resolve(String name) {
        if (name == null) {
            return Optional.empty();
        }
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return Optional.of(online.getUniqueId());
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        return cached == null ? Optional.empty() : Optional.of(cached.getUniqueId());
    }

    // ------------------------------------------------------------------ banks (unsupported)

    private static EconomyResponse noBanks() {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Banks are not supported");
    }

    @Override
    public EconomyResponse createBank(String name, String player) {
        return noBanks();
    }

    @Override
    public EconomyResponse createBank(String name, OfflinePlayer player) {
        return noBanks();
    }

    @Override
    public EconomyResponse deleteBank(String name) {
        return noBanks();
    }

    @Override
    public EconomyResponse bankBalance(String name) {
        return noBanks();
    }

    @Override
    public EconomyResponse bankHas(String name, double amount) {
        return noBanks();
    }

    @Override
    public EconomyResponse bankWithdraw(String name, double amount) {
        return noBanks();
    }

    @Override
    public EconomyResponse bankDeposit(String name, double amount) {
        return noBanks();
    }

    @Override
    public EconomyResponse isBankOwner(String name, String playerName) {
        return noBanks();
    }

    @Override
    public EconomyResponse isBankOwner(String name, OfflinePlayer player) {
        return noBanks();
    }

    @Override
    public EconomyResponse isBankMember(String name, String playerName) {
        return noBanks();
    }

    @Override
    public EconomyResponse isBankMember(String name, OfflinePlayer player) {
        return noBanks();
    }

    @Override
    public List<String> getBanks() {
        return List.of();
    }
}
