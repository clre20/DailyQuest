package clre20.dailyQuest.listener;

import clre20.dailyQuest.DailyQuest;
import clre20.dailyQuest.command.DqCommand;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

/**
 * 上線讀取資料、離線存檔並清除快取；登入提示（連勝中斷、新版本）。
 */
public final class PlayerListener implements Listener {

    private final DailyQuest plugin;

    public PlayerListener(DailyQuest plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        load(e.getPlayer());
        Player p = e.getPlayer();
        if (plugin.settings().updateNotifyOnJoin && !plugin.settings().updateRepository.isEmpty()
                && p.hasPermission(DqCommand.P_VERSION)) {
            UUID id = p.getUniqueId();
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Player again = Bukkit.getPlayer(id);
                if (again != null) plugin.updates().notifyJoin(again);
            }, 60L);
        }
    }

    public void load(Player p) {
        UUID id = p.getUniqueId();
        plugin.data().load(id, d -> {
            Player online = Bukkit.getPlayer(id);
            if (online == null || plugin.data().get(id) != null) {
                return; // 讀完前已經離線，或已經在快取裡
            }
            plugin.quest().onLoaded(online, d);
            if (plugin.settings().makeupLoginNotify) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    Player again = Bukkit.getPlayer(id);
                    if (again != null) plugin.quest().notifyBreak(again);
                }, Math.max(1L, plugin.settings().makeupLoginDelaySeconds * 20L));
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        plugin.quest().onQuit(e.getPlayer().getUniqueId());
    }
}
