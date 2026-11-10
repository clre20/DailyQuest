package clre20.dailyQuest.gui;

import clre20.dailyQuest.config.Messages;
import clre20.dailyQuest.config.PluginConfig;
import clre20.dailyQuest.data.DailyTask;
import clre20.dailyQuest.data.DataManager;
import clre20.dailyQuest.data.PlayerData;
import clre20.dailyQuest.quest.Definitions;
import clre20.dailyQuest.quest.QuestService;
import clre20.dailyQuest.quest.StreakTrack;
import clre20.dailyQuest.quest.TaskDef;
import clre20.dailyQuest.quest.Tier;
import clre20.dailyQuest.reward.RewardService;
import clre20.dailyQuest.reward.RewardSet;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * 箱子選單：主選單、重抽確認、蓋章地圖、連勝路線。全部用左鍵操作。
 */
public final class Menus {

    private static final int[] SMALL_TASK_SLOTS = {11, 12, 13, 14, 15, 20, 21, 22, 23, 24};
    private static final int[] LARGE_TASK_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
    private static final int CELLS_PER_PAGE = 45;

    // 主選單按鈕
    private static final int MAIN_INFO = 4;
    private static final int MAIN_MAP = 46;
    private static final int MAIN_STREAK = 48;
    private static final int MAIN_CLAIM_ALL = 50;
    private static final int MAIN_PENDING = 52;
    // 路線選單按鈕
    private static final int NAV_PREV = 45;
    private static final int NAV_INFO = 46;
    private static final int NAV_PATCH = 48;
    private static final int NAV_BACK = 49;
    private static final int NAV_SWITCH = 50;
    private static final int NAV_NEXT = 53;
    // 確認選單
    private static final int CONFIRM_YES = 11;
    private static final int CONFIRM_TASK = 13;
    private static final int CONFIRM_NO = 15;

    private final Plugin plugin;
    private final DataManager data;
    private final QuestService quest;
    private final RewardService rewards;
    private PluginConfig cfg;
    private Messages msg;
    private AdminMenus admin;

    public Menus(Plugin plugin, DataManager data, QuestService quest, RewardService rewards, PluginConfig cfg, Messages msg) {
        this.plugin = plugin;
        this.data = data;
        this.quest = quest;
        this.rewards = rewards;
        this.cfg = cfg;
        this.msg = msg;
    }

    public void reload(PluginConfig cfg, Messages msg) {
        this.cfg = cfg;
        this.msg = msg;
    }

    public void setAdmin(AdminMenus admin) {
        this.admin = admin;
    }

    private PlayerData dataOf(Player p) {
        PlayerData d = data.get(p.getUniqueId());
        if (d == null) {
            msg.send(p, "general.loading");
        }
        return d;
    }

    private Definitions defs() {
        return quest.defs();
    }

    // ------------------------------------------------------------ 開啟

    public void openMain(Player p) {
        PlayerData d = dataOf(p);
        if (d == null) return;
        Tier tier = tierOf(d);
        MenuHolder h = new MenuHolder(plugin, MenuHolder.Type.MAIN, 0, 0, 54,
                msg.get("gui.main.title", "tier", tier.display()));
        renderMain(p, d, h.getInventory());
        p.openInventory(h.getInventory());
    }

    public void openMap(Player p) {
        PlayerData d = dataOf(p);
        if (d == null) return;
        Tier tier = tierOf(d);
        int page = Math.min(d.stamps, tier.cells() - 1) / CELLS_PER_PAGE;
        MenuHolder h = new MenuHolder(plugin, MenuHolder.Type.MAP, page, 0, 54,
                msg.get("gui.map.title", "tier", tier.display()));
        renderMap(d, h);
        p.openInventory(h.getInventory());
    }

    public void openStreak(Player p) {
        PlayerData d = dataOf(p);
        if (d == null) return;
        StreakTrack track = defs().streak;
        int pos = track.displayPosition(d.streak);
        int page = Math.min(Math.max(pos, 0), track.length() - 1) / CELLS_PER_PAGE;
        MenuHolder h = new MenuHolder(plugin, MenuHolder.Type.STREAK, page, 0, 54, msg.get("gui.streak.title"));
        renderStreak(d, h);
        p.openInventory(h.getInventory());
    }

    private void openConfirm(Player p, PlayerData d, int index) {
        DailyTask t = d.tasks.get(index);
        TaskDef def = defs().task(t.key);
        if (def == null) return;
        MenuHolder h = new MenuHolder(plugin, MenuHolder.Type.CONFIRM_REROLL, 0, index, 27, msg.get("gui.confirm.title"));
        Inventory inv = h.getInventory();
        fill(inv);
        inv.setItem(CONFIRM_YES, item(Material.LIME_CONCRETE, msg.itemName("gui.confirm.accept.name"),
                msg.lore("gui.confirm.accept.lore", "left", String.valueOf(quest.rerollsLeft(d))), 1, false));
        inv.setItem(CONFIRM_TASK, taskItem(d, t, def));
        inv.setItem(CONFIRM_NO, item(Material.RED_CONCRETE, msg.itemName("gui.confirm.cancel.name"),
                msg.lore("gui.confirm.cancel.lore"), 1, false));
        p.openInventory(inv);
    }

    // ------------------------------------------------------------ 主選單

    private Tier tierOf(PlayerData d) {
        Tier t = defs().tier(d.tierId);
        return t != null ? t : defs().firstTier();
    }

    private int[] taskSlots(int n) {
        return n <= SMALL_TASK_SLOTS.length ? SMALL_TASK_SLOTS : LARGE_TASK_SLOTS;
    }

    private void renderMain(Player p, PlayerData d, Inventory inv) {
        inv.clear();
        fill(inv);
        Tier tier = tierOf(d);
        long left = quest.millisUntilReset() / 60000L;
        String reset = String.format("%02d:%02d", left / 60, left % 60);
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (head.getItemMeta() instanceof SkullMeta sm) {
            sm.setPlayerProfile(p.getPlayerProfile());
            head.setItemMeta(sm);
        }
        decorate(head, msg.itemName("gui.main.info.name", "player", p.getName()),
                msg.lore("gui.main.info.lore",
                        "tier", tier.display(),
                        "stamps", String.valueOf(d.stamps),
                        "cells", String.valueOf(tier.cells()),
                        "done", String.valueOf(d.completedCount()),
                        "total", String.valueOf(d.tasks.size()),
                        "threshold", String.valueOf(quest.stampThreshold(d)),
                        "stamped", msg.text(d.lastStampDay == quest.today() ? "gui.stamped-yes" : "gui.stamped-no"),
                        "streak", String.valueOf(d.streak),
                        "best", String.valueOf(d.bestStreak),
                        "tickets", String.valueOf(d.tickets),
                        "rerolls", String.valueOf(quest.rerollsLeft(d)),
                        "reset", reset), false);
        inv.setItem(MAIN_INFO, head);

        int[] slots = taskSlots(d.tasks.size());
        for (int i = 0; i < d.tasks.size() && i < slots.length; i++) {
            DailyTask t = d.tasks.get(i);
            TaskDef def = defs().task(t.key);
            inv.setItem(slots[i], def == null ? unknownTask() : taskItem(d, t, def));
        }
        if (d.tasks.isEmpty()) {
            inv.setItem(22, item(Material.BARRIER, msg.itemName("gui.main.no-tasks.name"), msg.lore("gui.main.no-tasks.lore"), 1, false));
        }

        inv.setItem(MAIN_MAP, item(tier.icon(), msg.itemName("gui.main.map.name"),
                msg.lore("gui.main.map.lore", "tier", tier.display(), "stamps", String.valueOf(d.stamps),
                        "cells", String.valueOf(tier.cells())), 1, false));
        int cost = quest.patchCost(d);
        inv.setItem(MAIN_STREAK, item(Material.BLAZE_POWDER, msg.itemName("gui.main.streak.name"),
                msg.lore(cost > 0 ? "gui.main.streak.lore-broken" : "gui.main.streak.lore",
                        "streak", String.valueOf(d.streak), "best", String.valueOf(d.bestStreak),
                        "cost", String.valueOf(cost), "tickets", String.valueOf(d.tickets)), 1, cost > 0));
        int unclaimed = d.unclaimedCount();
        inv.setItem(MAIN_CLAIM_ALL, item(unclaimed > 0 ? Material.HOPPER_MINECART : Material.MINECART,
                msg.itemName("gui.main.claim-all.name"),
                msg.lore("gui.main.claim-all.lore", "count", String.valueOf(unclaimed)), 1, unclaimed > 0));
        inv.setItem(MAIN_PENDING, item(d.pending.isEmpty() ? Material.BARREL : Material.ENDER_CHEST,
                msg.itemName("gui.main.pending.name"),
                msg.lore("gui.main.pending.lore", "count", String.valueOf(d.pending.size())),
                Math.max(1, Math.min(99, d.pending.size())), !d.pending.isEmpty()));
    }

    private ItemStack unknownTask() {
        return item(Material.BARRIER, msg.itemName("gui.task.unknown"), List.of(), 1, false);
    }

    private ItemStack taskItem(PlayerData d, DailyTask t, TaskDef def) {
        Tier tier = defs().tier(def.tierId());
        List<Component> lore = new ArrayList<>(msg.lore("gui.task.lore",
                "tier", tier == null ? def.tierId() : tier.display(),
                "bar", bar(t.progress, def.amount()),
                "progress", String.valueOf(t.progress),
                "amount", String.valueOf(def.amount())));
        RewardSet reward = quest.rewardOf(def, t.key);
        if (!reward.isEmpty() || reward.display != null) {
            lore.add(msg.itemName("gui.reward-header"));
            lore.addAll(rewards.describe(reward));
        }
        String status;
        if (t.claimed) {
            status = "gui.task.status-claimed";
        } else if (t.completed) {
            status = "gui.task.status-claim";
        } else if (quest.rerollsLeft(d) > 0) {
            status = "gui.task.status-reroll";
        } else {
            status = "gui.task.status-no-reroll";
        }
        lore.addAll(msg.lore(status, "left", String.valueOf(quest.rerollsLeft(d))));
        String nameKey = t.claimed ? "gui.task.name-claimed" : t.completed ? "gui.task.name-done" : "gui.task.name";
        Material icon = t.claimed ? Material.GRAY_DYE : def.icon();
        return item(icon, msg.itemName(nameKey, "task", quest.taskName(def)), lore, 1, t.completed && !t.claimed);
    }

    private String bar(int progress, int amount) {
        int segments = 10;
        int filled = amount <= 0 ? segments : (int) Math.min(segments, (long) progress * segments / amount);
        String on = msg.raw("gui.bar-filled");
        String off = msg.raw("gui.bar-empty");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments; i++) {
            sb.append(i < filled ? on : off);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ 蓋章地圖

    private void renderMap(PlayerData d, MenuHolder h) {
        Inventory inv = h.getInventory();
        inv.clear();
        Tier tier = tierOf(d);
        int pages = (tier.cells() + CELLS_PER_PAGE - 1) / CELLS_PER_PAGE;
        int page = Math.max(0, Math.min(h.page(), pages - 1));
        h.page(page);
        int start = page * CELLS_PER_PAGE;
        for (int i = 0; i < CELLS_PER_PAGE; i++) {
            int cell = start + i + 1;
            if (cell > tier.cells()) break;
            RewardSet reward = tier.cellReward(cell);
            boolean hasReward = !reward.isEmpty() || reward.display != null;
            boolean last = cell == tier.cells();
            String state;
            Material mat;
            String date = "";
            if (cell <= d.stamps) {
                state = "done";
                mat = hasReward ? cfg.cellDoneReward : cfg.cellDone;
                long day = cell - 1 < d.stampDays.size() ? d.stampDays.get(cell - 1) : 0;
                date = day > 0 ? quest.dateText(day) : msg.text("gui.unknown-date");
            } else if (cell == d.stamps + 1) {
                state = "next";
                mat = last ? cfg.cellFinal : hasReward ? cfg.cellLockedReward : cfg.cellNext;
            } else {
                state = "locked";
                mat = last ? cfg.cellFinal : hasReward ? cfg.cellLockedReward : cfg.cellLocked;
            }
            List<Component> lore = new ArrayList<>(msg.lore("gui.cell." + state + "-lore", "date", date));
            if (last) {
                lore.addAll(msg.lore("gui.map.final-lore"));
            }
            if (hasReward) {
                lore.add(msg.itemName("gui.reward-header"));
                lore.addAll(rewards.describe(reward));
            }
            inv.setItem(i, item(mat, msg.itemName("gui.cell." + state + "-name", "cell", String.valueOf(cell)),
                    lore, Math.min(99, cell), state.equals("next")));
        }
        // 階級列表
        List<Component> info = new ArrayList<>(msg.lore("gui.map.info.lore",
                "stamps", String.valueOf(d.stamps), "cells", String.valueOf(tier.cells()),
                "page", String.valueOf(page + 1), "pages", String.valueOf(pages)));
        for (Tier t : defs().tiers) {
            String key = t.index() < tier.index() ? "gui.map.tier-done" : t.index() == tier.index() ? "gui.map.tier-current" : "gui.map.tier-locked";
            info.add(msg.itemName(key, "tier", t.display(), "cells", String.valueOf(t.cells()),
                    "stamps", String.valueOf(d.stamps)));
        }
        navigation(inv, page, pages, item(tier.icon(), msg.itemName("gui.map.info.name", "tier", tier.display()), info, 1, false),
                item(Material.BLAZE_POWDER, msg.itemName("gui.nav.to-streak"), List.of(), 1, false));
    }

    // ------------------------------------------------------------ 連勝路線

    private void renderStreak(PlayerData d, MenuHolder h) {
        Inventory inv = h.getInventory();
        inv.clear();
        StreakTrack track = defs().streak;
        int pos = track.displayPosition(d.streak);
        int pages = (track.length() + CELLS_PER_PAGE - 1) / CELLS_PER_PAGE;
        int page = Math.max(0, Math.min(h.page(), pages - 1));
        h.page(page);
        int start = page * CELLS_PER_PAGE;
        for (int i = 0; i < CELLS_PER_PAGE; i++) {
            int cell = start + i + 1;
            if (cell > track.length()) break;
            RewardSet reward = track.rewardAt(cell);
            boolean hasReward = !reward.isEmpty() || reward.display != null;
            boolean last = cell == track.length();
            String state;
            Material mat;
            if (cell <= pos) {
                state = "done";
                mat = hasReward ? cfg.cellDoneReward : cfg.cellDone;
            } else if (cell == pos + 1) {
                state = "next";
                mat = last ? cfg.cellFinal : hasReward ? cfg.cellLockedReward : cfg.cellNext;
            } else {
                state = "locked";
                mat = last ? cfg.cellFinal : hasReward ? cfg.cellLockedReward : cfg.cellLocked;
            }
            List<Component> lore = new ArrayList<>(msg.lore("gui.streak.cell-" + state + "-lore"));
            if (last) {
                lore.addAll(msg.lore(track.loop() ? "gui.streak.final-lore-loop" : "gui.streak.final-lore-stay"));
            }
            if (hasReward) {
                lore.add(msg.itemName("gui.reward-header"));
                lore.addAll(rewards.describe(reward));
            }
            inv.setItem(i, item(mat, msg.itemName("gui.cell." + state + "-name", "cell", String.valueOf(cell)),
                    lore, Math.min(99, cell), state.equals("next")));
        }
        int cost = quest.patchCost(d);
        ItemStack info = item(Material.BLAZE_POWDER, msg.itemName("gui.streak.info.name"),
                msg.lore("gui.streak.info.lore", "streak", String.valueOf(d.streak), "best", String.valueOf(d.bestStreak),
                        "tickets", String.valueOf(d.tickets), "page", String.valueOf(page + 1),
                        "pages", String.valueOf(pages), "length", String.valueOf(track.length())), 1, false);
        navigation(inv, page, pages, info, item(tierOf(d).icon(), msg.itemName("gui.nav.to-map"), List.of(), 1, false));
        if (cost > 0) {
            inv.setItem(NAV_PATCH, item(Material.NAME_TAG, msg.itemName("gui.streak.patch.name"),
                    msg.lore("gui.streak.patch.lore", "prev", String.valueOf(d.brokenPrev), "cost", String.valueOf(cost),
                            "tickets", String.valueOf(d.tickets), "from", quest.dateText(d.brokenFrom),
                            "to", quest.dateText(d.brokenTo != 0 ? d.brokenTo : quest.today() - 1)), 1, true));
        }
    }

    private void navigation(Inventory inv, int page, int pages, ItemStack info, ItemStack switchItem) {
        ItemStack filler = filler();
        for (int s = 45; s < 54; s++) {
            inv.setItem(s, filler);
        }
        if (page > 0) {
            inv.setItem(NAV_PREV, item(Material.ARROW, msg.itemName("gui.nav.prev"), List.of(), 1, false));
        }
        if (page < pages - 1) {
            inv.setItem(NAV_NEXT, item(Material.ARROW, msg.itemName("gui.nav.next"), List.of(), 1, false));
        }
        inv.setItem(NAV_INFO, info);
        inv.setItem(NAV_BACK, item(Material.OAK_DOOR, msg.itemName("gui.nav.back"), List.of(), 1, false));
        inv.setItem(NAV_SWITCH, switchItem);
    }

    // ------------------------------------------------------------ 點擊

    /** 只會收到左鍵、點在上方選單內的點擊。 */
    public void click(Player p, MenuHolder h, int slot) {
        PlayerData d = data.get(p.getUniqueId());
        if (d == null) {
            p.closeInventory();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - d.lastClick < cfg.clickCooldownMs) {
            return;
        }
        d.lastClick = now;
        switch (h.type()) {
            case MAIN -> clickMain(p, d, h, slot);
            case CONFIRM_REROLL -> clickConfirm(p, d, h, slot);
            case MAP -> clickTrack(p, d, h, slot, true);
            case STREAK -> clickTrack(p, d, h, slot, false);
            case ADMIN_LIST, ADMIN_PLAYER, ADMIN_TASKS -> {
                if (admin != null) admin.click(p, h, slot);
            }
        }
    }

    private void clickMain(Player p, PlayerData d, MenuHolder h, int slot) {
        switch (slot) {
            case MAIN_MAP -> openMap(p);
            case MAIN_STREAK -> openStreak(p);
            case MAIN_CLAIM_ALL -> {
                int n = quest.claimAll(p, d);
                if (n == 0) {
                    msg.send(p, "claim.nothing");
                    quest.play(p, cfg.soundError);
                }
                renderMain(p, d, h.getInventory());
            }
            case MAIN_PENDING -> {
                if (d.pending.isEmpty()) {
                    msg.send(p, "pending.empty");
                } else {
                    int got = rewards.claimPending(p, d);
                    msg.send(p, d.pending.isEmpty() ? "pending.claimed" : "pending.claimed-partial",
                            "count", String.valueOf(got), "left", String.valueOf(d.pending.size()));
                    quest.play(p, got > 0 ? cfg.soundClaim : cfg.soundError);
                }
                renderMain(p, d, h.getInventory());
            }
            default -> {
                int[] slots = taskSlots(d.tasks.size());
                for (int i = 0; i < slots.length && i < d.tasks.size(); i++) {
                    if (slots[i] != slot) continue;
                    DailyTask t = d.tasks.get(i);
                    if (t.completed) {
                        if (quest.claim(p, d, i) == QuestService.ClaimResult.ALREADY) {
                            msg.send(p, "claim.already");
                        }
                        renderMain(p, d, h.getInventory());
                    } else if (quest.rerollsLeft(d) <= 0) {
                        msg.send(p, "reroll.no-rerolls");
                        quest.play(p, cfg.soundError);
                    } else if (defs().task(t.key) != null) {
                        openConfirm(p, d, i);
                    }
                    return;
                }
            }
        }
    }

    private void clickConfirm(Player p, PlayerData d, MenuHolder h, int slot) {
        if (slot == CONFIRM_NO) {
            openMain(p);
            return;
        }
        if (slot != CONFIRM_YES) {
            return;
        }
        QuestService.RerollResult r = quest.reroll(p, d, h.arg());
        switch (r) {
            case NO_REROLLS -> msg.send(p, "reroll.no-rerolls");
            case COMPLETED -> msg.send(p, "reroll.completed");
            case NO_CANDIDATE -> msg.send(p, "reroll.no-candidate");
            case INVALID -> msg.send(p, "reroll.invalid");
            case OK -> { }
        }
        if (r != QuestService.RerollResult.OK) {
            quest.play(p, cfg.soundError);
        }
        openMain(p);
    }

    private void clickTrack(Player p, PlayerData d, MenuHolder h, int slot, boolean map) {
        switch (slot) {
            case NAV_PREV -> {
                h.page(h.page() - 1);
                rerenderTrack(d, h, map);
            }
            case NAV_NEXT -> {
                h.page(h.page() + 1);
                rerenderTrack(d, h, map);
            }
            case NAV_BACK -> openMain(p);
            case NAV_SWITCH -> {
                if (map) openStreak(p);
                else openMap(p);
            }
            case NAV_PATCH -> {
                if (map) return;
                QuestService.PatchResult r = quest.patch(p, d);
                switch (r) {
                    case NONE -> msg.send(p, "streak.patch-none");
                    case EXPIRED -> msg.send(p, "streak.patch-expired");
                    case NOT_ENOUGH -> msg.send(p, "streak.patch-not-enough",
                            "cost", String.valueOf(quest.patchCost(d)), "tickets", String.valueOf(d.tickets));
                    case OK -> { }
                }
                if (r != QuestService.PatchResult.OK) {
                    quest.play(p, cfg.soundError);
                }
                rerenderTrack(d, h, false);
            }
            default -> { }
        }
    }

    private void rerenderTrack(PlayerData d, MenuHolder h, boolean map) {
        if (map) renderMap(d, h);
        else renderStreak(d, h);
    }

    // ------------------------------------------------------------ 物品工具

    ItemStack filler() {
        ItemStack it = new ItemStack(cfg.guiFiller);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text(" "));
        meta.setHideTooltip(true);
        it.setItemMeta(meta);
        return it;
    }

    void fill(Inventory inv) {
        ItemStack f = filler();
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, f);
        }
    }

    static ItemStack item(Material m, Component name, List<Component> lore, int amount, boolean glow) {
        ItemStack it = new ItemStack(m);
        decorate(it, name, lore, glow);
        it.setAmount(Math.max(1, Math.min(99, amount)));
        return it;
    }

    static void decorate(ItemStack it, Component name, List<Component> lore, boolean glow) {
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return;
        meta.displayName(name);
        meta.lore(lore);
        meta.setMaxStackSize(99);
        meta.addItemFlags(ItemFlag.values());
        if (glow) {
            meta.setEnchantmentGlintOverride(true);
        }
        it.setItemMeta(meta);
    }
}
