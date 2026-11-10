package clre20.dailyQuest.quest;

import clre20.dailyQuest.reward.RewardSet;

import java.util.Map;

/**
 * 連勝路線（streak.yml）。
 */
public record StreakTrack(int length, boolean loop, int interval, RewardSet intervalReward,
                          Map<Integer, RewardSet> dayRewards) {

    /** 連勝天數在路線上的位置（1 起算）；0＝還沒開始；STAY 模式超過長度回傳 -1（沒有路線獎勵）。 */
    public int position(int streak) {
        if (streak <= 0) {
            return 0;
        }
        if (streak <= length) {
            return streak;
        }
        return loop ? ((streak - 1) % length) + 1 : -1;
    }

    /** 選單顯示用：STAY 模式超過長度時停在最後一格。 */
    public int displayPosition(int streak) {
        int p = position(streak);
        return p < 0 ? length : p;
    }

    public RewardSet rewardAt(int position) {
        if (position <= 0) {
            return RewardSet.EMPTY;
        }
        RewardSet r = dayRewards.get(position);
        if (r != null) {
            return r;
        }
        if (interval > 0 && position % interval == 0) {
            return intervalReward;
        }
        return RewardSet.EMPTY;
    }
}
