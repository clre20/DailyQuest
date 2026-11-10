package clre20.dailyQuest.data;

import java.util.UUID;

/**
 * 玩家資料的儲存方式。所有方法都只在 I/O 執行緒（或關服時的主執行緒）呼叫，不需要額外同步。
 */
public interface Storage {

    void init() throws Exception;

    /** 讀取玩家資料；沒有資料回傳 null。 */
    String load(UUID uuid) throws Exception;

    void save(UUID uuid, String data, long lastSeenDay) throws Exception;

    /** 刪除最後上線日早於 beforeDay 的玩家資料，回傳刪除筆數。 */
    int purge(long beforeDay) throws Exception;

    void close();
}
