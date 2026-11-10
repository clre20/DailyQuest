package clre20.dailyQuest.hook;

import clre20.dailyQuest.DailyQuest;

/**
 * 只有確認 PlaceholderAPI 已安裝時才會呼叫這個類別，避免沒裝時載入 PlaceholderAPI 的類別。
 */
public final class PapiHook {

    private PapiHook() {
    }

    /** 註冊變數，回傳取消註冊的動作；失敗回傳 null。 */
    public static Runnable register(DailyQuest plugin) {
        PapiExpansion exp = new PapiExpansion(plugin);
        return exp.register() ? exp::unregister : null;
    }
}
