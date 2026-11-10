package clre20.dailyQuest.quest;

import clre20.dailyQuest.config.Messages;
import clre20.dailyQuest.config.PluginConfig;
import clre20.dailyQuest.data.DailyTask;
import clre20.dailyQuest.data.DataManager;
import clre20.dailyQuest.data.PlayerData;
import clre20.dailyQuest.reward.RewardService;
import clre20.dailyQuest.reward.RewardSet;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.Statistic;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 每日任務的核心規則：抽任務、進度、完成、領獎、重抽、蓋章、升階、連勝與補簽。
 * 全部在主執行緒執行。
 */
public final class QuestService {

    private static final TaskType[] TYPES = TaskType.all();

    private final DataManager data;
    private final RewardService rewards;
    private PluginConfig cfg;
    private Messages msg;
    private Definitions defs;

    /** 每種任務類型目前有幾位線上玩家有未完成的任務；為 0 時事件直接略過。 */
    private final int[] activeCount = new int[TYPES.length];
    /** 有取樣類任務的線上玩家。 */
    private final Set<UUID> sampled = new HashSet<>();

    private long today;
    private long nextBoundaryMillis;

    public QuestService(DataManager data, RewardService rewards, PluginConfig cfg, Messages msg, Definitions defs) {
        this.data = data;
        this.rewards = rewards;
        this.cfg = cfg;
        this.msg = msg;
        this.defs = defs;
        recalcDay();
    }

    public void reload(PluginConfig cfg, Messages msg, Definitions defs) {
        this.cfg = cfg;
        this.msg = msg;
        this.defs = defs;
        recalcDay();
        for (PlayerData d : data.cached()) {
            if (!rollover(d)) {
                repairTasks(d);
            }
            recomputeMask(d);
        }
    }

    public Definitions defs() {
        return defs;
    }

    public PluginConfig cfg() {
        return cfg;
    }

    // ------------------------------------------------------------ 日期

    public long today() {
        return today;
    }

    /** 測試用：-Ddailyquest.debug.dayLengthSeconds=60 讓「一天」只有 60 秒。正式環境不要設定。 */
    private static final long DEBUG_DAY_SECONDS = Long.getLong("dailyquest.debug.dayLengthSeconds", 0L);
    private static final long DEBUG_START_SLOT = DEBUG_DAY_SECONDS > 0
            ? System.currentTimeMillis() / 1000L / DEBUG_DAY_SECONDS : 0L;

    private void recalcDay() {
        if (DEBUG_DAY_SECONDS > 0) {
            long sec = System.currentTimeMillis() / 1000L;
            // 從 2026-01-01 起算，日期顯示仍是合理的日期
            today = LocalDate.of(2026, 1, 1).toEpochDay() + (sec / DEBUG_DAY_SECONDS - DEBUG_START_SLOT);
            nextBoundaryMillis = (sec / DEBUG_DAY_SECONDS + 1) * DEBUG_DAY_SECONDS * 1000L;
            return;
        }
        ZonedDateTime now = ZonedDateTime.now(cfg.zone);
        LocalDate d = now.toLocalDate();
        if (now.toLocalTime().isBefore(cfg.resetTime)) {
            d = d.minusDays(1);
        }
        today = d.toEpochDay();
        ZonedDateTime next = d.plusDays(1).atTime(cfg.resetTime).atZone(cfg.zone);
        nextBoundaryMillis = next.toInstant().toEpochMilli();
    }

    /** 每秒呼叫一次：過了重置時間就幫所有線上玩家換新的一天。 */
    public void tick() {
        if (System.currentTimeMillis() < nextBoundaryMillis) {
            return;
        }
        recalcDay();
        for (PlayerData d : data.cached()) {
            if (rollover(d)) {
                recomputeMask(d);
                Player p = Bukkit.getPlayer(d.uuid);
                if (p != null) {
                    msg.send(p, "daily.reset");
                }
            }
        }
    }

    public String dateText(long epochDay) {
        return LocalDate.ofEpochDay(epochDay).toString();
    }

    public long millisUntilReset() {
        return Math.max(0, nextBoundaryMillis - Instant.now().toEpochMilli());
    }

    // ------------------------------------------------------------ 玩家上下線

    public void onLoaded(Player p, PlayerData d) {
        d.name = p.getName();
        if (d.lastSeenDay != today) {
            d.lastSeenDay = today;
            d.dirty = true;
        }
        data.put(d);
        if (!rollover(d)) {
            repairTasks(d);
        }
        recomputeMask(d);
    }

    public void onQuit(UUID uuid) {
        PlayerData d = data.get(uuid);
        if (d != null) {
            setMask(d, 0);
        }
        sampled.remove(uuid);
        data.unload(uuid);
    }

    // ------------------------------------------------------------ 換日

    /** 如果資料還不是今天，就處理連勝中斷、升階、重新抽任務。回傳是否換了日。 */
    public boolean rollover(PlayerData d) {
        if (d.day == today && d.tierId != null && defs.tier(d.tierId) != null) {
            return false;
        }
        long yesterday = today - 1;
        // 連勝中斷
        if (d.streak > 0 && d.lastStreakDay < yesterday) {
            d.brokenPrev = d.streak;
            d.brokenFrom = d.lastStreakDay + 1;
            d.brokenTo = 0;
            d.streak = 0;
        }
        checkBreakExpiry(d);

        // 階級（階級被刪掉時回到第一階）
        Tier tier = defs.tier(d.tierId);
        if (tier == null) {
            tier = defs.firstTier();
            d.tierId = tier.id();
            d.stamps = Math.min(d.stamps, tier.cells());
        }
        Tier next;
        while (d.stamps >= tier.cells() && (next = defs.next(tier)) != null) {
            d.stamps = 0;
            d.stampDays.clear();
            tier = next;
            d.tierId = tier.id();
        }

        if (d.day != today) {
            d.day = today;
            d.rerollsUsed = 0;
            d.allCompleteGiven = false;
            d.seenToday.clear();
            d.tasks.clear();
            for (TaskDef t : pick(defs.pool(tier.id()), cfg.tasksPerDay, d.seenToday)) {
                d.tasks.add(new DailyTask(t.key()));
                d.seenToday.add(t.key());
            }
        }
        d.dirty = true;
        return true;
    }

    /** 中斷超過可補天數或過了補簽期限就清掉紀錄。 */
    private void checkBreakExpiry(PlayerData d) {
        if (d.brokenPrev <= 0) {
            return;
        }
        long to = d.brokenTo != 0 ? d.brokenTo : today - 1;
        long missed = to - d.brokenFrom + 1;
        if (missed > cfg.makeupMaxMissed || missed <= 0
                || (d.brokenTo != 0 && today - d.brokenTo > cfg.makeupExpireDays)) {
            clearBreak(d);
        }
    }

    private static void clearBreak(PlayerData d) {
        d.brokenPrev = 0;
        d.brokenFrom = 0;
        d.brokenTo = 0;
    }

    /** 設定檔重載後，換掉已不存在的任務（不扣重抽次數）。 */
    private void repairTasks(PlayerData d) {
        boolean changed = false;
        for (DailyTask t : d.tasks) {
            if (defs.task(t.key) != null || t.completed) {
                continue;
            }
            String tierId = tierOfKey(t.key);
            List<TaskDef> pool = defs.pool(defs.tier(tierId) != null ? tierId : d.tierId);
            List<TaskDef> one = pick(pool, 1, d.seenToday);
            if (!one.isEmpty()) {
                t.key = one.getFirst().key();
                t.progress = 0;
                d.seenToday.add(t.key);
                changed = true;
            }
        }
        if (changed) {
            d.dirty = true;
        }
    }

    private static String tierOfKey(String key) {
        int i = key.indexOf('/');
        return i < 0 ? key : key.substring(0, i);
    }

    /** 依權重抽 n 個不重複、不在 exclude 內的任務。 */
    private static List<TaskDef> pick(List<TaskDef> pool, int n, Set<String> exclude) {
        List<TaskDef> candidates = new ArrayList<>(pool.size());
        long total = 0;
        for (TaskDef t : pool) {
            if (!exclude.contains(t.key())) {
                candidates.add(t);
                total += t.weight();
            }
        }
        List<TaskDef> out = new ArrayList<>(Math.min(n, candidates.size()));
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        while (out.size() < n && !candidates.isEmpty()) {
            long r = rnd.nextLong(total);
            int idx = 0;
            for (; idx < candidates.size() - 1; idx++) {
                r -= candidates.get(idx).weight();
                if (r < 0) break;
            }
            TaskDef chosen = candidates.remove(idx);
            total -= chosen.weight();
            out.add(chosen);
        }
        return out;
    }

    // ------------------------------------------------------------ 進度

    public boolean hasActive(TaskType type) {
        return activeCount[type.ordinal()] > 0;
    }

    public Set<UUID> sampledPlayers() {
        return sampled;
    }

    /** 重新計算玩家「有哪些未完成類型」，並更新全域計數。 */
    public void recomputeMask(PlayerData d) {
        long mask = 0;
        if (d.day == today) {
            for (DailyTask t : d.tasks) {
                if (t.completed) continue;
                TaskDef def = defs.task(t.key);
                if (def != null) mask |= def.type().bit;
            }
        }
        setMask(d, mask);
    }

    private void setMask(PlayerData d, long mask) {
        long old = d.activeMask;
        if (old == mask) {
            return;
        }
        long changed = old ^ mask;
        for (TaskType t : TYPES) {
            if ((changed & t.bit) == 0) continue;
            if ((mask & t.bit) != 0) {
                activeCount[t.ordinal()]++;
                d.statSnapshot[t.ordinal()] = -1L; // 新任務從現在開始算
                d.statRemainder[t.ordinal()] = 0;
            } else {
                activeCount[t.ordinal()]--;
            }
        }
        d.activeMask = mask;
        if ((mask & TaskType.SAMPLED_MASK) != 0) {
            sampled.add(d.uuid);
        } else {
            sampled.remove(d.uuid);
        }
    }

    /** 事件共用的前置檢查：資料存在、今天、有這種任務、不在停用世界、不是創造模式。 */
    private PlayerData eligible(Player p, TaskType type) {
        PlayerData d = data.get(p.getUniqueId());
        if (d == null || (d.activeMask & type.bit) == 0 || d.day != today) {
            return null;
        }
        if (cfg.ignoreCreative && p.getGameMode() == GameMode.CREATIVE) {
            return null;
        }
        if (!cfg.disabledWorlds.isEmpty()
                && cfg.disabledWorlds.contains(p.getWorld().getName().toLowerCase(java.util.Locale.ROOT))) {
            return null;
        }
        return d;
    }

    public void progress(Player p, TaskType type, Material m, int amount) {
        if (amount <= 0) return;
        PlayerData d = eligible(p, type);
        if (d == null) return;
        for (int i = 0; i < d.tasks.size(); i++) {
            DailyTask t = d.tasks.get(i);
            if (t.completed) continue;
            TaskDef def = defs.task(t.key);
            if (def != null && def.type() == type && def.matches(m)) {
                add(p, d, t, def, amount);
            }
        }
    }

    public void progress(Player p, TaskType type, EntityType e, int amount) {
        if (amount <= 0) return;
        PlayerData d = eligible(p, type);
        if (d == null) return;
        for (int i = 0; i < d.tasks.size(); i++) {
            DailyTask t = d.tasks.get(i);
            if (t.completed) continue;
            TaskDef def = defs.task(t.key);
            if (def != null && def.type() == type && def.matches(e)) {
                add(p, d, t, def, amount);
            }
        }
    }

    public void progress(Player p, TaskType type, int amount) {
        if (amount <= 0) return;
        PlayerData d = eligible(p, type);
        if (d == null) return;
        for (int i = 0; i < d.tasks.size(); i++) {
            DailyTask t = d.tasks.get(i);
            if (t.completed) continue;
            TaskDef def = defs.task(t.key);
            if (def != null && def.type() == type) {
                add(p, d, t, def, amount);
            }
        }
    }

    /** 取樣類任務：比較統計值差距，換算成單位（格／分鐘）。 */
    public void sample(Player p) {
        PlayerData d = data.get(p.getUniqueId());
        if (d == null) return;
        long mask = d.activeMask & TaskType.SAMPLED_MASK;
        if (mask == 0) return;
        boolean counts = d.day == today && eligibleIgnoringMask(p);
        for (TaskType type : TYPES) {
            if ((mask & type.bit) == 0) continue;
            int o = type.ordinal();
            long now = 0;
            for (Statistic s : type.stats) {
                now += p.getStatistic(s);
            }
            long before = d.statSnapshot[o];
            d.statSnapshot[o] = now;
            if (before < 0 || now <= before || !counts) {
                continue;
            }
            long delta = now - before + d.statRemainder[o];
            int units = (int) Math.min(Integer.MAX_VALUE, delta / type.divisor);
            d.statRemainder[o] = (int) (delta % type.divisor);
            if (units > 0) {
                progress(p, type, units);
            }
        }
    }

    private boolean eligibleIgnoringMask(Player p) {
        if (cfg.ignoreCreative && p.getGameMode() == GameMode.CREATIVE) {
            return false;
        }
        return cfg.disabledWorlds.isEmpty()
                || !cfg.disabledWorlds.contains(p.getWorld().getName().toLowerCase(java.util.Locale.ROOT));
    }

    private void add(Player p, PlayerData d, DailyTask t, TaskDef def, int amount) {
        t.progress = (int) Math.min(def.amount(), (long) t.progress + amount);
        d.dirty = true;
        if (t.progress >= def.amount()) {
            complete(p, d, t, def);
            return;
        }
        if (cfg.actionbar) {
            long now = System.currentTimeMillis();
            if (now - d.lastActionbar >= cfg.actionbarIntervalMs) {
                d.lastActionbar = now;
                p.sendActionBar(msg.get("progress.actionbar",
                        "task", taskName(def), "progress", String.valueOf(t.progress), "amount", String.valueOf(def.amount())));
            }
        }
    }

    public String taskName(TaskDef def) {
        return Messages.apply(def.display(), "amount", String.valueOf(def.amount()));
    }

    private void complete(Player p, PlayerData d, DailyTask t, TaskDef def) {
        t.completed = true;
        t.progress = def.amount();
        d.dirty = true;
        recomputeMask(d);
        int done = d.completedCount();
        msg.send(p, cfg.autoClaim ? "task.completed-auto" : "task.completed",
                "task", taskName(def), "done", String.valueOf(done), "total", String.valueOf(d.tasks.size()));
        play(p, cfg.soundComplete);
        if (cfg.autoClaim) {
            claim(p, d, d.tasks.indexOf(t));
        }
        afterCompletion(p, d);
    }

    private void afterCompletion(Player p, PlayerData d) {
        int done = d.completedCount();
        int threshold = stampThreshold(d);
        if (done >= threshold && d.lastStampDay != today) {
            stamp(p, d);
        }
        if (done >= d.tasks.size() && !d.tasks.isEmpty() && !d.allCompleteGiven) {
            d.allCompleteGiven = true;
            rewards.give(p, d, defs.allComplete, "reward.title-all-complete");
        }
    }

    public int stampThreshold(PlayerData d) {
        return Math.max(1, Math.min(cfg.stampThreshold, d.tasks.size()));
    }

    // ------------------------------------------------------------ 領獎

    public enum ClaimResult { OK, NOT_DONE, ALREADY, INVALID }

    public ClaimResult claim(Player p, PlayerData d, int index) {
        if (index < 0 || index >= d.tasks.size()) {
            return ClaimResult.INVALID;
        }
        DailyTask t = d.tasks.get(index);
        if (!t.completed) return ClaimResult.NOT_DONE;
        if (t.claimed) return ClaimResult.ALREADY;
        t.claimed = true;
        d.dirty = true;
        TaskDef def = defs.task(t.key);
        RewardSet reward = rewardOf(def, t.key);
        play(p, cfg.soundClaim);
        rewards.give(p, d, reward, "reward.title-task", "task", def == null ? t.key : taskName(def));
        return ClaimResult.OK;
    }

    public RewardSet rewardOf(TaskDef def, String key) {
        if (def != null && def.reward() != null) {
            return def.reward();
        }
        Tier tier = defs.tier(def != null ? def.tierId() : tierOfKey(key));
        return tier == null ? RewardSet.EMPTY : tier.taskReward();
    }

    /** 一次領取所有已完成的任務獎勵。 */
    public int claimAll(Player p, PlayerData d) {
        int n = 0;
        for (int i = 0; i < d.tasks.size(); i++) {
            if (claim(p, d, i) == ClaimResult.OK) n++;
        }
        return n;
    }

    // ------------------------------------------------------------ 重抽

    public enum RerollResult { OK, NO_REROLLS, COMPLETED, NO_CANDIDATE, INVALID }

    public RerollResult reroll(Player p, PlayerData d, int index) {
        if (index < 0 || index >= d.tasks.size() || d.day != today) {
            return RerollResult.INVALID;
        }
        if (d.rerollsUsed >= cfg.rerollsPerDay) {
            return RerollResult.NO_REROLLS;
        }
        DailyTask t = d.tasks.get(index);
        if (t.completed) {
            return RerollResult.COMPLETED;
        }
        String tierId = tierOfKey(t.key);
        if (defs.tier(tierId) == null) {
            tierId = d.tierId;
        }
        List<TaskDef> one = pick(defs.pool(tierId), 1, d.seenToday);
        if (one.isEmpty()) {
            return RerollResult.NO_CANDIDATE;
        }
        TaskDef def = one.getFirst();
        d.tasks.set(index, new DailyTask(def.key()));
        d.seenToday.add(def.key());
        d.rerollsUsed++;
        d.dirty = true;
        recomputeMask(d);
        play(p, cfg.soundReroll);
        msg.send(p, "reroll.success", "task", taskName(def),
                "left", String.valueOf(rerollsLeft(d)));
        return RerollResult.OK;
    }

    public int rerollsLeft(PlayerData d) {
        return Math.max(0, cfg.rerollsPerDay - d.rerollsUsed);
    }

    // ------------------------------------------------------------ 蓋章與升階

    private void stamp(Player p, PlayerData d) {
        d.lastStampDay = today;
        d.dirty = true;
        Tier tier = defs.tier(d.tierId);
        if (tier == null) {
            tier = defs.firstTier();
            d.tierId = tier.id();
        }
        play(p, cfg.soundStamp);

        // 進度路線
        if (d.stamps < tier.cells()) {
            d.stamps++;
            d.stampDays.add(today);
            msg.send(p, "stamp.stamped", "tier", tier.display(),
                    "stamps", String.valueOf(d.stamps), "cells", String.valueOf(tier.cells()));
            rewards.give(p, d, defs.everyStamp, "reward.title-stamp");
            rewards.give(p, d, tier.cellReward(d.stamps), "reward.title-cell",
                    "tier", tier.display(), "cell", String.valueOf(d.stamps));
            if (d.stamps >= tier.cells()) {
                Tier next = defs.next(tier);
                if (next != null) {
                    d.tierId = next.id();
                    d.stamps = 0;
                    d.stampDays.clear();
                    broadcastOrSend(p, "stamp.tier-up", "tier", tier.display(), "next", next.display());
                } else {
                    msg.send(p, "stamp.max-tier", "tier", tier.display());
                }
            }
        } else {
            msg.send(p, "stamp.stamped-max", "tier", tier.display());
            rewards.give(p, d, defs.everyStamp, "reward.title-stamp");
        }

        // 連勝路線
        if (d.brokenPrev > 0 && d.brokenTo == 0) {
            d.brokenTo = today - 1; // 中斷到昨天為止，之後仍可在期限內補簽
        }
        if (d.lastStreakDay == today - 1 && d.streak > 0) {
            d.streak++;
        } else {
            d.streak = 1;
        }
        d.lastStreakDay = today;
        d.bestStreak = Math.max(d.bestStreak, d.streak);
        int pos = defs.streak.position(d.streak);
        msg.send(p, "streak.increased", "streak", String.valueOf(d.streak));
        rewards.give(p, d, defs.streak.rewardAt(pos), "reward.title-streak",
                "streak", String.valueOf(d.streak), "position", String.valueOf(pos));
    }

    private void broadcastOrSend(Player p, String key, String... kv) {
        String body = msg.text(key, kv);
        if (body.isEmpty()) return;
        Component c = Messages.color(msg.raw("prefix") + Messages.apply(body, "player", p.getName()));
        if (cfg.broadcastTierUp) {
            Bukkit.getServer().sendMessage(c);
        } else {
            p.sendMessage(c);
        }
    }

    // ------------------------------------------------------------ 補簽

    public enum PatchResult { OK, NONE, EXPIRED, NOT_ENOUGH }

    /** 目前中斷需要幾張補簽卷；0＝沒有可補的中斷。 */
    public int patchCost(PlayerData d) {
        if (d.brokenPrev <= 0) return 0;
        long to = d.brokenTo != 0 ? d.brokenTo : today - 1;
        long missed = to - d.brokenFrom + 1;
        if (missed <= 0 || missed > cfg.makeupMaxMissed) return 0;
        if (d.brokenTo != 0 && today - d.brokenTo > cfg.makeupExpireDays) return 0;
        return (int) missed;
    }

    public PatchResult patch(Player p, PlayerData d) {
        if (d.brokenPrev <= 0) return PatchResult.NONE;
        int cost = patchCost(d);
        if (cost <= 0) {
            clearBreak(d);
            d.dirty = true;
            return PatchResult.EXPIRED;
        }
        if (d.tickets < cost) return PatchResult.NOT_ENOUGH;
        d.tickets -= cost;
        int prev = d.brokenPrev;
        int base = prev + (cfg.makeupCountPatched ? cost : 0);
        int newStreak;
        if (d.streak > 0 && d.brokenTo != 0) {
            newStreak = base + d.streak; // 中斷後已經重新開始，接起來
        } else {
            newStreak = base;
            d.lastStreakDay = d.brokenTo != 0 ? d.brokenTo : today - 1;
        }
        d.streak = newStreak;
        d.bestStreak = Math.max(d.bestStreak, newStreak);
        clearBreak(d);
        d.dirty = true;
        msg.send(p, "streak.patched", "cost", String.valueOf(cost), "streak", String.valueOf(newStreak),
                "tickets", String.valueOf(d.tickets));
        play(p, cfg.soundClaim);
        // 補回中間經過的路線獎勵
        for (int s = prev + 1; s <= newStreak; s++) {
            int pos = defs.streak.position(s);
            RewardSet r = defs.streak.rewardAt(pos);
            if (!r.isEmpty()) {
                rewards.give(p, d, r, "reward.title-streak", "streak", String.valueOf(s), "position", String.valueOf(pos));
            }
        }
        return PatchResult.OK;
    }

    // ------------------------------------------------------------ 管理員

    public void adminReset(PlayerData d) {
        d.day = 0;
        rollover(d);
        recomputeMask(d);
    }

    public boolean adminComplete(Player p, PlayerData d, int index) {
        if (index < 0 || index >= d.tasks.size()) return false;
        DailyTask t = d.tasks.get(index);
        TaskDef def = defs.task(t.key);
        if (t.completed || def == null) return false;
        complete(p, d, t, def);
        return true;
    }

    public void adminSetTier(PlayerData d, Tier tier) {
        d.tierId = tier.id();
        d.stamps = 0;
        d.stampDays.clear();
        d.dirty = true;
    }

    public void adminSetStamps(PlayerData d, int stamps) {
        Tier tier = defs.tier(d.tierId);
        if (tier == null) {
            tier = defs.firstTier();
            d.tierId = tier.id();
        }
        int n = Math.max(0, Math.min(stamps, tier.cells()));
        while (d.stampDays.size() > n) d.stampDays.removeLast();
        while (d.stampDays.size() < n) d.stampDays.add(0L);
        d.stamps = n;
        d.dirty = true;
    }

    public void adminSetStreak(PlayerData d, int streak) {
        d.streak = Math.max(0, streak);
        d.lastStreakDay = d.streak > 0 ? (d.lastStampDay == today ? today : today - 1) : 0;
        d.bestStreak = Math.max(d.bestStreak, d.streak);
        clearBreak(d);
        d.dirty = true;
    }

    // ------------------------------------------------------------ 其他

    public void play(Player p, PluginConfig.SoundSpec s) {
        if (s != null) {
            p.playSound(p.getLocation(), s.key(), SoundCategory.MASTER, s.volume(), s.pitch());
        }
    }

    public void notifyBreak(Player p) {
        PlayerData d = data.get(p.getUniqueId());
        if (d == null) return;
        int cost = patchCost(d);
        if (cost <= 0) return;
        msg.send(p, "streak.broken-login", "prev", String.valueOf(d.brokenPrev), "cost", String.valueOf(cost),
                "tickets", String.valueOf(d.tickets));
    }
}
