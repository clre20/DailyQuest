package clre20.dailyQuest.economy;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * 所有用到 Vault 類別的程式都在這裡；只有確認 Vault 已安裝時才會載入這個類別，
 * 沒裝 Vault 也不會出現 NoClassDefFoundError。
 */
final class VaultBridge {

    private final Economy economy;

    private VaultBridge(Economy economy) {
        this.economy = economy;
    }

    static VaultBridge find() {
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        return rsp == null ? null : new VaultBridge(rsp.getProvider());
    }

    /** 回傳 null＝成功，否則是錯誤訊息。 */
    String deposit(Player p, double amount) {
        EconomyResponse r = economy.depositPlayer(p, amount);
        return r.transactionSuccess() ? null : String.valueOf(r.errorMessage);
    }

    String format(double amount) {
        return economy.format(amount);
    }
}
