package clre20.dailyQuest.gui;

import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.Plugin;

/**
 * DailyQuest 所有箱子選單的 InventoryHolder，也是插件簽署：
 * 其他插件可以用 {@code holder instanceof MenuHolder}，或檢查 {@link #getSignature()}／{@link #getPlugin()}
 * 判斷這個選單屬於 DailyQuest。
 */
public final class MenuHolder implements InventoryHolder {

    public static final String SIGNATURE = "clre20:dailyquest";

    public enum Type { MAIN, CONFIRM_REROLL, MAP, STREAK, ADMIN_LIST, ADMIN_PLAYER, ADMIN_TASKS }

    private final Plugin plugin;
    private final Type type;
    private int page;
    private final int arg;
    private final Inventory inventory;
    /** 管理選單：被管理的玩家（只存 UUID 與名稱）。 */
    private java.util.UUID target;
    private String targetName = "";
    /** 管理選單：離線玩家的資料副本（只在選單開著時存在）。 */
    private clre20.dailyQuest.data.PlayerData snapshot;

    MenuHolder(Plugin plugin, Type type, int page, int arg, int size, net.kyori.adventure.text.Component title) {
        this.plugin = plugin;
        this.type = type;
        this.page = page;
        this.arg = arg;
        this.inventory = Bukkit.createInventory(this, size, title);
    }

    public Plugin getPlugin() {
        return plugin;
    }

    public String getSignature() {
        return SIGNATURE;
    }

    public Type type() {
        return type;
    }

    public int page() {
        return page;
    }

    void page(int page) {
        this.page = page;
    }

    public int arg() {
        return arg;
    }

    public java.util.UUID target() {
        return target;
    }

    public String targetName() {
        return targetName;
    }

    void target(java.util.UUID target, String name) {
        this.target = target;
        this.targetName = name == null ? "" : name;
    }

    clre20.dailyQuest.data.PlayerData snapshot() {
        return snapshot;
    }

    void snapshot(clre20.dailyQuest.data.PlayerData snapshot) {
        this.snapshot = snapshot;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
