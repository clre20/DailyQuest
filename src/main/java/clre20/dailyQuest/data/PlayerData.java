package clre20.dailyQuest.data;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 一位玩家的資料。只在主執行緒修改；存檔時先在主執行緒轉成字串再交給 I/O 執行緒。
 * 日期一律用 epochDay（long），0＝沒有。
 */
public final class PlayerData {

    public final UUID uuid;
    public String name = "";

    // ---- 蓋章地圖 ----
    public String tierId;
    public int stamps;
    /** 本階每一格蓋章的日期（升階後清空，最多等於該階格數）。 */
    public final List<Long> stampDays = new ArrayList<>();
    public long lastStampDay;

    // ---- 連勝 ----
    public int streak;
    public int bestStreak;
    public long lastStreakDay;
    /** 中斷紀錄：中斷前的連勝天數；0＝沒有可補的中斷。 */
    public int brokenPrev;
    public long brokenFrom;
    /** 中斷的最後一天；0＝還沒重新開始（到昨天為止都算中斷）。 */
    public long brokenTo;
    public int tickets;

    // ---- 今天 ----
    public long day;
    public final List<DailyTask> tasks = new ArrayList<>();
    public int rerollsUsed;
    public final Set<String> seenToday = new HashSet<>();
    public boolean allCompleteGiven;

    /** 背包滿時暫存的物品（Base64）。 */
    public final List<String> pending = new ArrayList<>();
    public long lastSeenDay;

    // ---- 不存檔 ----
    public transient boolean dirty;
    public transient long activeMask;
    /** 取樣類任務上次的統計值；-1＝還沒取樣。索引是 TaskType.ordinal()。 */
    public final transient long[] statSnapshot;
    public final transient int[] statRemainder;
    public transient long lastActionbar;
    public transient long lastClick;
    public transient long lastCommand;
    /** 讀取失敗時為 true：這次上線期間不寫回，避免蓋掉原本的資料。 */
    public transient boolean readOnly;

    public PlayerData(UUID uuid, int typeCount) {
        this.uuid = uuid;
        this.statSnapshot = new long[typeCount];
        this.statRemainder = new int[typeCount];
        java.util.Arrays.fill(statSnapshot, -1L);
    }

    public int completedCount() {
        int n = 0;
        for (DailyTask t : tasks) {
            if (t.completed) n++;
        }
        return n;
    }

    public int unclaimedCount() {
        int n = 0;
        for (DailyTask t : tasks) {
            if (t.completed && !t.claimed) n++;
        }
        return n;
    }

    // ------------------------------------------------------------ 序列化

    public String serialize() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("v", 1);
        y.set("name", name);
        y.set("tier", tierId);
        y.set("stamps", stamps);
        y.set("stamp-days", stampDays);
        y.set("last-stamp", lastStampDay);
        y.set("streak", streak);
        y.set("best-streak", bestStreak);
        y.set("last-streak", lastStreakDay);
        if (brokenPrev > 0) {
            y.set("broken.prev", brokenPrev);
            y.set("broken.from", brokenFrom);
            y.set("broken.to", brokenTo);
        }
        y.set("tickets", tickets);
        y.set("day", day);
        List<String> list = new ArrayList<>(tasks.size());
        for (DailyTask t : tasks) {
            list.add(t.key + ";" + t.progress + ";" + (t.completed ? 1 : 0) + ";" + (t.claimed ? 1 : 0));
        }
        y.set("tasks", list);
        y.set("rerolls-used", rerollsUsed);
        y.set("seen", new ArrayList<>(seenToday));
        y.set("all-complete-given", allCompleteGiven);
        if (!pending.isEmpty()) {
            y.set("pending", pending);
        }
        y.set("last-seen", lastSeenDay);
        return y.saveToString();
    }

    public static PlayerData deserialize(UUID uuid, String text, int typeCount) throws InvalidConfigurationException {
        PlayerData d = new PlayerData(uuid, typeCount);
        if (text == null || text.isEmpty()) {
            return d;
        }
        YamlConfiguration y = new YamlConfiguration();
        y.loadFromString(text);
        d.name = y.getString("name", "");
        d.tierId = y.getString("tier");
        d.stamps = Math.max(0, y.getInt("stamps"));
        for (Object o : y.getList("stamp-days", List.of())) {
            if (o instanceof Number n) d.stampDays.add(n.longValue());
        }
        d.lastStampDay = y.getLong("last-stamp");
        d.streak = Math.max(0, y.getInt("streak"));
        d.bestStreak = Math.max(0, y.getInt("best-streak"));
        d.lastStreakDay = y.getLong("last-streak");
        ConfigurationSection b = y.getConfigurationSection("broken");
        if (b != null) {
            d.brokenPrev = Math.max(0, b.getInt("prev"));
            d.brokenFrom = b.getLong("from");
            d.brokenTo = b.getLong("to");
        }
        d.tickets = Math.max(0, y.getInt("tickets"));
        d.day = y.getLong("day");
        for (String s : y.getStringList("tasks")) {
            String[] p = s.split(";");
            if (p.length < 4) continue;
            try {
                d.tasks.add(new DailyTask(p[0], Math.max(0, Integer.parseInt(p[1])), "1".equals(p[2]), "1".equals(p[3])));
            } catch (NumberFormatException ignored) {
                // 略過壞掉的項目
            }
        }
        d.rerollsUsed = Math.max(0, y.getInt("rerolls-used"));
        d.seenToday.addAll(y.getStringList("seen"));
        d.allCompleteGiven = y.getBoolean("all-complete-given");
        d.pending.addAll(y.getStringList("pending"));
        d.lastSeenDay = y.getLong("last-seen");
        return d;
    }
}
