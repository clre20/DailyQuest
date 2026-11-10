package clre20.dailyQuest.data;

/**
 * 玩家今天的一個任務。只在主執行緒修改。
 */
public final class DailyTask {

    public String key;
    public int progress;
    public boolean completed;
    public boolean claimed;

    public DailyTask(String key) {
        this.key = key;
    }

    public DailyTask(String key, int progress, boolean completed, boolean claimed) {
        this.key = key;
        this.progress = progress;
        this.completed = completed;
        this.claimed = claimed;
    }
}
