package clre20.dailyQuest.quest;

import clre20.dailyQuest.reward.RewardSet;
import org.bukkit.Material;

import java.util.Map;

/**
 * 一個階級（蓋章地圖的一段）。
 *
 * @param index       由 0 開始的順序
 * @param cells       蓋滿幾格升階
 * @param taskReward  這一階任務的預設完成獎勵
 * @param cellRewards 第幾格（1 起算）的獎勵
 */
public record Tier(String id, int index, String display, Material icon, int cells,
                   RewardSet taskReward, Map<Integer, RewardSet> cellRewards) {

    public RewardSet cellReward(int cell) {
        return cellRewards.getOrDefault(cell, RewardSet.EMPTY);
    }
}
