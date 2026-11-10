package clre20.dailyQuest.quest;

import org.bukkit.Statistic;

import java.util.Locale;

/**
 * 任務類型。少於 64 種，玩家身上「有哪些未完成類型」用一個 long 位元遮罩表示，事件裡可以 O(1) 判斷。
 */
public enum TaskType {
    BREAK_BLOCK(Target.MATERIAL),
    PLACE_BLOCK(Target.MATERIAL),
    HARVEST(Target.MATERIAL),
    KILL_ENTITY(Target.ENTITY),
    CRAFT(Target.MATERIAL),
    SMELT(Target.MATERIAL),
    FISH(Target.MATERIAL),
    BREED(Target.ENTITY),
    TAME(Target.ENTITY),
    SHEAR(Target.ENTITY),
    MILK(Target.ENTITY),
    TRADE(Target.MATERIAL),
    ENCHANT(Target.MATERIAL),
    ANVIL(Target.MATERIAL),
    SMITHING(Target.MATERIAL),
    BREW(Target.MATERIAL),
    CONSUME(Target.MATERIAL),
    PICKUP(Target.MATERIAL),
    SHOOT_BOW(Target.NONE),
    THROW(Target.ENTITY),
    GAIN_EXP(Target.NONE),
    LEVEL_UP(Target.NONE),
    JUMP(Target.NONE),
    SLEEP(Target.NONE),
    RAID_WIN(Target.NONE),
    // 以下由統計值定時取樣
    WALK(100, Statistic.WALK_ONE_CM),
    SPRINT(100, Statistic.SPRINT_ONE_CM),
    SWIM(100, Statistic.SWIM_ONE_CM),
    CLIMB(100, Statistic.CLIMB_ONE_CM),
    BOAT(100, Statistic.BOAT_ONE_CM),
    MINECART(100, Statistic.MINECART_ONE_CM),
    RIDE(100, Statistic.HORSE_ONE_CM, Statistic.PIG_ONE_CM, Statistic.STRIDER_ONE_CM),
    ELYTRA(100, Statistic.AVIATE_ONE_CM),
    PLAYTIME(1200, Statistic.PLAY_ONE_MINUTE);

    public enum Target { MATERIAL, ENTITY, NONE }

    private static final TaskType[] VALUES = values();
    public static final long SAMPLED_MASK;

    static {
        long m = 0;
        for (TaskType t : VALUES) {
            if (t.sampled()) {
                m |= t.bit;
            }
        }
        SAMPLED_MASK = m;
    }

    public final Target target;
    public final long bit;
    /** 取樣類：統計值除以多少等於一個單位（cm→格＝100，tick→分鐘＝1200）。 */
    public final int divisor;
    public final Statistic[] stats;

    TaskType(Target target) {
        this.target = target;
        this.bit = 1L << ordinal();
        this.divisor = 1;
        this.stats = new Statistic[0];
    }

    TaskType(int divisor, Statistic... stats) {
        this.target = Target.NONE;
        this.bit = 1L << ordinal();
        this.divisor = divisor;
        this.stats = stats;
    }

    public boolean sampled() {
        return stats.length > 0;
    }

    public static TaskType[] all() {
        return VALUES;
    }

    public static TaskType parse(String s) {
        if (s == null) {
            return null;
        }
        try {
            return valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
