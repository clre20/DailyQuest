package clre20.dailyQuest.gui;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;

/**
 * 選單防呆：只要上方是 DailyQuest 的選單，所有點擊與拖曳一律取消；只有左鍵點上方格子才會觸發功能。
 * 功能延後一個 tick 執行（在點擊事件中直接開關選單並不安全）。
 */
public final class GuiListener implements Listener {

    private final Plugin plugin;
    private final Menus menus;

    public GuiListener(Plugin plugin, Menus menus) {
        this.plugin = plugin;
        this.menus = menus;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder(false) instanceof MenuHolder holder)) {
            return;
        }
        e.setCancelled(true);
        if (e.getClick() != ClickType.LEFT || e.getClickedInventory() != top) {
            return;
        }
        if (!(e.getWhoClicked() instanceof Player p)) {
            return;
        }
        int slot = e.getRawSlot();
        Bukkit.getScheduler().runTask(plugin, () -> {
            // 玩家可能已關閉選單或下線
            if (p.isOnline() && p.getOpenInventory().getTopInventory() == top) {
                menus.click(p, holder, slot);
            }
        });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder(false) instanceof MenuHolder) {
            e.setCancelled(true);
        }
    }
}
