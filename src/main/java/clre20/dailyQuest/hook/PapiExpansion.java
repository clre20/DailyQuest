package clre20.dailyQuest.hook;

import clre20.dailyQuest.DailyQuest;
import clre20.dailyQuest.config.Messages;
import clre20.dailyQuest.data.PlayerData;
import clre20.dailyQuest.quest.QuestService;
import clre20.dailyQuest.quest.Tier;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.OfflinePlayer;

import java.util.Locale;

/**
 * PlaceholderAPI 變數：%dailyquest_&lt;名稱&gt;%。只讀記憶體快取（線上玩家），不讀硬碟。
 * 只有安裝 PlaceholderAPI 時才會載入這個類別。
 */
public final class PapiExpansion extends PlaceholderExpansion {

    private final DailyQuest plugin;

    public PapiExpansion(DailyQuest plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "dailyquest";
    }

    @Override
    public String getAuthor() {
        return "Clre20";
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (player == null) return "";
        try {
            return value(player, params);
        } catch (RuntimeException e) {
            // 其他執行緒讀取時資料剛好在變動，下次再取
            return "";
        }
    }

    private String value(OfflinePlayer player, String params) {
        PlayerData d = plugin.data().get(player.getUniqueId());
        if (d == null) return "";
        QuestService q = plugin.quest();
        Tier tier = q.defs().tier(d.tierId);
        return switch (params.toLowerCase(Locale.ROOT)) {
            case "tier" -> tier == null ? "" : LegacyComponentSerializer.legacySection().serialize(Messages.color(tier.display()));
            case "tier_id" -> d.tierId == null ? "" : d.tierId;
            case "tier_index" -> tier == null ? "0" : String.valueOf(tier.index() + 1);
            case "stamps" -> String.valueOf(d.stamps);
            case "stamps_required" -> tier == null ? "0" : String.valueOf(tier.cells());
            case "streak" -> String.valueOf(d.streak);
            case "best_streak" -> String.valueOf(d.bestStreak);
            case "completed" -> String.valueOf(d.completedCount());
            case "total" -> String.valueOf(d.tasks.size());
            case "rerolls_left" -> String.valueOf(q.rerollsLeft(d));
            case "tickets" -> String.valueOf(d.tickets);
            case "stamped_today" -> String.valueOf(d.lastStampDay == q.today());
            case "streak_broken" -> String.valueOf(q.patchCost(d) > 0);
            case "patch_cost" -> String.valueOf(q.patchCost(d));
            default -> null;
        };
    }
}
