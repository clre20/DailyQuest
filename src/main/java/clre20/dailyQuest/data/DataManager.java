package clre20.dailyQuest.data;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * 記憶體快取＋髒資料標記＋單一 I/O 執行緒。
 * <ul>
 *   <li>快取只在主執行緒讀寫。</li>
 *   <li>所有磁碟／資料庫存取都排進同一條 I/O 執行緒，所以同一位玩家的讀寫一定依序進行。</li>
 *   <li>定時存檔只寫有變動的玩家；離線時存一次；關服時同步寫完。</li>
 * </ul>
 */
public final class DataManager {

    private final JavaPlugin plugin;
    private final Storage storage;
    private final int typeCount;
    /** 主執行緒寫入；PlaceholderAPI 可能在其他執行緒讀取，所以用 ConcurrentHashMap。 */
    private final Map<UUID, PlayerData> cache = new ConcurrentHashMap<>();
    private final ExecutorService io;
    private volatile boolean closed;

    public DataManager(JavaPlugin plugin, Storage storage, int typeCount) {
        this.plugin = plugin;
        this.storage = storage;
        this.typeCount = typeCount;
        this.io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "DailyQuest-IO");
            t.setDaemon(true);
            return t;
        });
    }

    public PlayerData get(UUID uuid) {
        return cache.get(uuid);
    }

    public Collection<PlayerData> cached() {
        return cache.values();
    }

    /** 讀取玩家資料，完成後在主執行緒呼叫 callback（讀取失敗時給空白資料並在後台警告）。 */
    public void load(UUID uuid, Consumer<PlayerData> callback) {
        submit(() -> {
            PlayerData data;
            try {
                data = PlayerData.deserialize(uuid, storage.load(uuid), typeCount);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Cannot load data of " + uuid
                        + ", the player gets temporary empty data and nothing is saved for this session", e);
                data = new PlayerData(uuid, typeCount);
                data.readOnly = true;
            }
            PlayerData result = data;
            runMain(() -> callback.accept(result));
        });
    }

    public void put(PlayerData data) {
        cache.put(data.uuid, data);
    }

    /** 玩家離線：存檔並移出快取。 */
    public PlayerData unload(UUID uuid) {
        PlayerData d = cache.remove(uuid);
        if (d != null && d.dirty && !d.readOnly) {
            d.dirty = false;
            String text = d.serialize();
            long seen = d.lastSeenDay;
            submit(() -> write(uuid, text, seen));
        }
        return d;
    }

    /** 定時存檔：主執行緒轉字串，I/O 執行緒一次寫完。 */
    public void saveDirty() {
        List<Object[]> batch = new ArrayList<>();
        for (PlayerData d : cache.values()) {
            if (d.dirty && !d.readOnly) {
                d.dirty = false;
                batch.add(new Object[]{d.uuid, d.serialize(), d.lastSeenDay});
            }
        }
        if (batch.isEmpty()) {
            return;
        }
        submit(() -> {
            for (Object[] o : batch) {
                write((UUID) o[0], (String) o[1], (long) o[2]);
            }
        });
    }

    /**
     * 修改不在快取中的玩家（例如管理員給離線玩家補簽卷）。
     * 在 I/O 執行緒讀取、修改、寫回；之後在主執行緒若發現玩家已經讀進快取，對快取再套用一次
     * （因為讀取和修改在同一條執行緒依序進行，快取裡的是修改前的版本）。
     */
    public void modifyOffline(UUID uuid, Consumer<PlayerData> change, Consumer<PlayerData> done) {
        submit(() -> {
            PlayerData d;
            try {
                d = PlayerData.deserialize(uuid, storage.load(uuid), typeCount);
                change.accept(d);
                storage.save(uuid, d.serialize(), d.lastSeenDay);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Cannot modify data of " + uuid, e);
                return;
            }
            runMain(() -> {
                PlayerData cached = cache.get(uuid);
                if (cached != null) {
                    change.accept(cached);
                    cached.dirty = true;
                }
                done.accept(cached != null ? cached : d);
            });
        });
    }

    public void purge(long beforeDay) {
        submit(() -> {
            try {
                int n = storage.purge(beforeDay);
                if (n > 0) {
                    plugin.getLogger().info("Removed data of " + n + " inactive players");
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Cannot purge inactive data", e);
            }
        });
    }

    /** 關服：等 I/O 佇列做完，再同步寫入所有快取中的資料。 */
    public void shutdown() {
        closed = true;
        io.shutdown();
        try {
            if (!io.awaitTermination(15, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("I/O queue did not finish in 15 seconds");
                io.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (PlayerData d : cache.values()) {
            if (d.dirty && !d.readOnly) {
                d.dirty = false;
                write(d.uuid, d.serialize(), d.lastSeenDay);
            }
        }
        cache.clear();
        storage.close();
    }

    private void write(UUID uuid, String text, long lastSeen) {
        try {
            storage.save(uuid, text, lastSeen);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Cannot save data of " + uuid, e);
        }
    }

    private void submit(Runnable r) {
        if (closed) {
            return;
        }
        try {
            io.execute(r);
        } catch (RejectedExecutionException ignored) {
            // 關服中
        }
    }

    private void runMain(Runnable r) {
        if (!plugin.isEnabled()) {
            return;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, r);
        } catch (org.bukkit.plugin.IllegalPluginAccessException ignored) {
            // 插件剛好在關閉
        }
    }
}
