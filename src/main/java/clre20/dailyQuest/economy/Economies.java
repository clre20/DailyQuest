package clre20.dailyQuest.economy;

import clre20.dailyQuest.config.PluginConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 錢（Vault）與第二貨幣（ExcellentEconomy）。
 * ExcellentEconomy 沒有公開的 Maven 倉庫可以下載，所以用反射呼叫它的 API：
 * {@code ExcellentEconomyAPI#deposit(Player, String, double)}；找不到時可改用後台指令。
 * 兩者都在第一次使用時才尋找，找不到會在下次使用時再試一次。
 */
public final class Economies {

    private final JavaPlugin plugin;
    private PluginConfig cfg;

    private VaultBridge vault;
    private Object eeApi;
    private Method eeDeposit;
    private long eeNextTry;
    private boolean warnedVault;
    private boolean warnedCurrency;

    public Economies(JavaPlugin plugin, PluginConfig cfg) {
        this.plugin = plugin;
        this.cfg = cfg;
    }

    public void reload(PluginConfig cfg) {
        this.cfg = cfg;
        this.vault = null;
        this.eeApi = null;
        this.eeDeposit = null;
        this.eeNextTry = 0;
        this.warnedVault = false;
        this.warnedCurrency = false;
    }

    // ------------------------------------------------------------ Vault

    private VaultBridge vault() {
        if (!cfg.vault) {
            return null;
        }
        if (vault == null && Bukkit.getPluginManager().getPlugin("Vault") != null) {
            vault = VaultBridge.find();
        }
        return vault;
    }

    public boolean depositMoney(Player p, double amount) {
        VaultBridge eco = vault();
        if (eco == null) {
            if (!warnedVault) {
                warnedVault = true;
                plugin.getLogger().warning("Money reward skipped: Vault or an economy plugin is not available");
            }
            return false;
        }
        String error = eco.deposit(p, amount);
        if (error != null) {
            plugin.getLogger().warning("Vault deposit to " + p.getName() + " failed: " + error);
            return false;
        }
        return true;
    }

    /** 錢的顯示格式：有經濟插件就用它的 format()。 */
    public String formatMoney(double amount) {
        VaultBridge eco = vault();
        if (eco != null) {
            try {
                return eco.format(amount);
            } catch (RuntimeException ignored) {
                // 用預設格式
            }
        }
        return plain(amount);
    }

    // ------------------------------------------------------------ 第二貨幣

    public boolean currencyEnabled() {
        return cfg.currencyEnabled;
    }

    public double normalizeCurrency(double amount) {
        return cfg.currencyDecimal ? amount : Math.floor(amount);
    }

    public boolean depositCurrency(Player p, double amount) {
        if (!cfg.currencyEnabled) {
            return false;
        }
        double value = normalizeCurrency(amount);
        if (value <= 0) {
            return true;
        }
        if ("EXCELLENT_ECONOMY".equals(cfg.currencyProvider) && resolveExcellent()) {
            try {
                Object ok = eeDeposit.invoke(eeApi, p, cfg.currencyId, value);
                if (Boolean.TRUE.equals(ok)) {
                    return true;
                }
                plugin.getLogger().warning("ExcellentEconomy deposit of '" + cfg.currencyId + "' to "
                        + p.getName() + " returned false (wrong currency-id?)");
            } catch (ReflectiveOperationException | RuntimeException e) {
                plugin.getLogger().warning("ExcellentEconomy API call failed: " + e);
                eeApi = null;
                eeDeposit = null;
            }
        }
        return fallbackCommand(p, value);
    }

    private boolean fallbackCommand(Player p, double value) {
        String cmd = cfg.currencyFallbackCommand;
        if (cmd == null || cmd.isBlank()) {
            if (!warnedCurrency) {
                warnedCurrency = true;
                plugin.getLogger().warning("Second currency reward skipped: ExcellentEconomy API not available and "
                        + "economy.second-currency.fallback-command is empty");
            }
            return false;
        }
        String line = cmd.replace("%player%", p.getName()).replace("%amount%", plain(value));
        if (line.startsWith("/")) {
            line = line.substring(1);
        }
        return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line);
    }

    private boolean resolveExcellent() {
        if (eeApi != null && eeDeposit != null) {
            return true;
        }
        long now = System.currentTimeMillis();
        if (now < eeNextTry) {
            return false;
        }
        eeNextTry = now + 60_000L;
        if (Bukkit.getPluginManager().getPlugin("ExcellentEconomy") == null) {
            return false;
        }
        ServicesManager sm = Bukkit.getServicesManager();
        for (Class<?> c : sm.getKnownServices()) {
            if (!c.getSimpleName().equals("ExcellentEconomyAPI")) {
                continue;
            }
            RegisteredServiceProvider<?> rsp = sm.getRegistration(c);
            if (rsp == null) {
                continue;
            }
            try {
                Method m = c.getMethod("deposit", Player.class, String.class, double.class);
                eeApi = rsp.getProvider();
                eeDeposit = m;
                plugin.getLogger().info("Hooked into ExcellentEconomy API");
                return true;
            } catch (NoSuchMethodException e) {
                plugin.getLogger().warning("ExcellentEconomyAPI#deposit(Player, String, double) not found");
            }
        }
        return false;
    }

    public static String plain(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.DOWN).stripTrailingZeros().toPlainString();
    }
}
