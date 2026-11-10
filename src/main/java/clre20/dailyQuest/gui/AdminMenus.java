package clre20.dailyQuest.gui;

import clre20.dailyQuest.DailyQuest;
import clre20.dailyQuest.command.DqCommand;
import clre20.dailyQuest.config.Messages;
import clre20.dailyQuest.data.DailyTask;
import clre20.dailyQuest.data.PlayerData;
import clre20.dailyQuest.quest.Definitions;
import clre20.dailyQuest.quest.QuestService;
import clre20.dailyQuest.quest.TaskDef;
import clre20.dailyQuest.quest.Tier;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 管理選單（需要 dailyquest.admin.manage）：
 * <ul>
 *   <li>玩家列表：線上玩家頭顱，可翻頁。</li>
 *   <li>玩家面板：階級切換、章數／連勝／補簽卷加減按鈕、重新抽任務、今日任務。</li>
 *   <li>今日任務：左鍵直接完成（玩家需在線）。</li>
 * </ul>
 * 離線玩家的資料只在選單開著時以副本存在；每次修改都經由 I/O 執行緒讀取、修改、寫回。
 * 每次點擊都重新檢查權限，權限被拿掉時立刻關閉選單。
 */
public final class AdminMenus {

    private static final int PER_PAGE = 45;
    private static final int MAX_VALUE = 1_000_000;

    // 列表
    private static final int L_PREV = 45;
    private static final int L_INFO = 49;
    private static final int L_NEXT = 53;
    // 玩家面板
    private static final int P_HEAD = 4;
    private static final int P_TIER_PREV = 11;
    private static final int P_TIER = 13;
    private static final int P_TIER_NEXT = 15;
    private static final int[] P_STAMPS = {20, 21, 22, 23, 24};
    private static final int[] P_STREAK = {29, 30, 31, 32, 33};
    private static final int[] P_TICKETS = {38, 39, 40, 41, 42};
    private static final int[] DELTAS = {-10, -1, 0, 1, 10};
    private static final int P_BACK = 45;
    private static final int P_RESET = 48;
    private static final int P_TASKS = 50;
    private static final int P_REFRESH = 53;
    // 今日任務
    private static final int[] TASK_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
    private static final int T_BACK = 49;

    private final DailyQuest plugin;
    private final Menus menus;

    public AdminMenus(DailyQuest plugin, Menus menus) {
        this.plugin = plugin;
        this.menus = menus;
    }

    private Messages msg() {
        return plugin.messages();
    }

    private QuestService quest() {
        return plugin.quest();
    }

    private Definitions defs() {
        return plugin.quest().defs();
    }

    private static boolean allowed(Player p) {
        return p.hasPermission(DqCommand.P_MANAGE);
    }

    // ------------------------------------------------------------ 列表

    public void openList(Player admin, int page) {
        MenuHolder h = new MenuHolder(plugin, MenuHolder.Type.ADMIN_LIST, page, 0, 54, msg().get("gui.admin.list.title"));
        renderList(h);
        admin.openInventory(h.getInventory());
    }

    private List<Player> onlineSorted() {
        List<Player> list = new ArrayList<>(Bukkit.getOnlinePlayers());
        list.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        return list;
    }

    private void renderList(MenuHolder h) {
        Inventory inv = h.getInventory();
        inv.clear();
        List<Player> players = onlineSorted();
        int pages = Math.max(1, (players.size() + PER_PAGE - 1) / PER_PAGE);
        int page = Math.max(0, Math.min(h.page(), pages - 1));
        h.page(page);
        for (int i = 0; i < PER_PAGE; i++) {
            int idx = page * PER_PAGE + i;
            if (idx >= players.size()) break;
            Player t = players.get(idx);
            PlayerData d = plugin.data().get(t.getUniqueId());
            List<Component> lore = d == null ? msg().lore("gui.admin.list.loading") : summary(d);
            lore = new ArrayList<>(lore);
            lore.addAll(msg().lore("gui.admin.list.click"));
            inv.setItem(i, head(t, msg().itemName("gui.admin.list.player", "player", t.getName()), lore));
        }
        ItemStack filler = menus.filler();
        for (int s = 45; s < 54; s++) inv.setItem(s, filler);
        if (page > 0) inv.setItem(L_PREV, Menus.item(Material.ARROW, msg().itemName("gui.nav.prev"), List.of(), 1, false));
        if (page < pages - 1) inv.setItem(L_NEXT, Menus.item(Material.ARROW, msg().itemName("gui.nav.next"), List.of(), 1, false));
        inv.setItem(L_INFO, Menus.item(Material.BOOK, msg().itemName("gui.admin.list.info.name"),
                msg().lore("gui.admin.list.info.lore", "online", String.valueOf(players.size()),
                        "page", String.valueOf(page + 1), "pages", String.valueOf(pages)), 1, false));
    }

    private List<Component> summary(PlayerData d) {
        Tier tier = defs().tier(d.tierId);
        return msg().lore("gui.admin.summary",
                "tier", tier == null ? String.valueOf(d.tierId) : tier.display(),
                "stamps", String.valueOf(d.stamps),
                "cells", tier == null ? "?" : String.valueOf(tier.cells()),
                "streak", String.valueOf(d.streak),
                "best", String.valueOf(d.bestStreak),
                "tickets", String.valueOf(d.tickets),
                "done", String.valueOf(d.day == quest().today() ? d.completedCount() : 0),
                "total", String.valueOf(d.day == quest().today() ? d.tasks.size() : 0),
                "rerolls", String.valueOf(quest().rerollsLeft(d)),
                "broken", d.brokenPrev > 0 ? String.valueOf(d.brokenPrev) : "-");
    }

    private static ItemStack head(Player online, Component name, List<Component> lore) {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        if (online != null && it.getItemMeta() instanceof SkullMeta sm) {
            sm.setPlayerProfile(online.getPlayerProfile());
            it.setItemMeta(sm);
        }
        Menus.decorate(it, name, lore, false);
        return it;
    }

    // ------------------------------------------------------------ 玩家面板

    /** 線上玩家直接開；離線玩家先在 I/O 執行緒讀取副本再開。 */
    public void openPlayer(Player admin, UUID target, String name) {
        PlayerData cached = plugin.data().get(target);
        if (cached != null) {
            showPlayer(admin, target, name, null);
            return;
        }
        msg().send(admin, "gui.admin.loading-offline", "player", name);
        UUID adminId = admin.getUniqueId();
        plugin.data().load(target, d -> {
            Player a = Bukkit.getPlayer(adminId);
            if (a != null && allowed(a)) {
                showPlayer(a, target, name, plugin.data().get(target) != null ? null : d);
            }
        });
    }

    private void showPlayer(Player admin, UUID target, String name, PlayerData snapshot) {
        MenuHolder h = new MenuHolder(plugin, MenuHolder.Type.ADMIN_PLAYER, 0, 0, 54,
                msg().get("gui.admin.player.title", "player", name));
        h.target(target, name);
        h.snapshot(snapshot);
        renderPlayer(h);
        admin.openInventory(h.getInventory());
    }

    /** 目前的資料：在線（快取）優先，否則用副本。 */
    private PlayerData dataOf(MenuHolder h) {
        PlayerData cached = plugin.data().get(h.target());
        if (cached != null) {
            h.snapshot(null);
            return cached;
        }
        return h.snapshot();
    }

    private void renderPlayer(MenuHolder h) {
        Inventory inv = h.getInventory();
        inv.clear();
        menus.fill(inv);
        PlayerData d = dataOf(h);
        if (d == null) {
            inv.setItem(22, Menus.item(Material.BARRIER, msg().itemName("gui.admin.player.no-data"), List.of(), 1, false));
            inv.setItem(P_BACK, Menus.item(Material.OAK_DOOR, msg().itemName("gui.admin.back"), List.of(), 1, false));
            return;
        }
        Player online = Bukkit.getPlayer(h.target());
        List<Component> headLore = new ArrayList<>(summary(d));
        headLore.addAll(msg().lore(online != null ? "gui.admin.player.online" : "gui.admin.player.offline"));
        inv.setItem(P_HEAD, head(online, msg().itemName("gui.admin.list.player", "player", h.targetName()), headLore));

        // 階級
        Tier tier = defs().tier(d.tierId);
        if (tier == null) tier = defs().firstTier();
        Tier prev = tier.index() > 0 ? defs().tiers.get(tier.index() - 1) : null;
        Tier next = defs().next(tier);
        inv.setItem(P_TIER, Menus.item(tier.icon(), msg().itemName("gui.admin.player.tier.name", "tier", tier.display()),
                msg().lore("gui.admin.player.tier.lore", "stamps", String.valueOf(d.stamps),
                        "cells", String.valueOf(tier.cells()), "index", String.valueOf(tier.index() + 1),
                        "count", String.valueOf(defs().tiers.size())), 1, false));
        if (prev != null) {
            inv.setItem(P_TIER_PREV, Menus.item(Material.RED_STAINED_GLASS_PANE,
                    msg().itemName("gui.admin.player.tier-prev.name", "tier", prev.display()),
                    msg().lore("gui.admin.player.tier-change-lore"), 1, false));
        }
        if (next != null) {
            inv.setItem(P_TIER_NEXT, Menus.item(Material.LIME_STAINED_GLASS_PANE,
                    msg().itemName("gui.admin.player.tier-next.name", "tier", next.display()),
                    msg().lore("gui.admin.player.tier-change-lore"), 1, false));
        }

        numberRow(inv, P_STAMPS, Material.FILLED_MAP, "stamps", d.stamps, "/" + tier.cells());
        numberRow(inv, P_STREAK, Material.BLAZE_POWDER, "streak", d.streak, "");
        numberRow(inv, P_TICKETS, Material.NAME_TAG, "tickets", d.tickets, "");

        inv.setItem(P_BACK, Menus.item(Material.OAK_DOOR, msg().itemName("gui.admin.back"), List.of(), 1, false));
        boolean on = online != null;
        inv.setItem(P_RESET, Menus.item(on ? Material.ENDER_EYE : Material.BARRIER,
                msg().itemName("gui.admin.player.reset.name"),
                msg().lore(on ? "gui.admin.player.reset.lore" : "gui.admin.player.need-online"), 1, false));
        inv.setItem(P_TASKS, Menus.item(on ? Material.WRITABLE_BOOK : Material.BARRIER,
                msg().itemName("gui.admin.player.tasks.name"),
                msg().lore(on ? "gui.admin.player.tasks.lore" : "gui.admin.player.need-online",
                        "done", String.valueOf(d.completedCount()), "total", String.valueOf(d.tasks.size())), 1, false));
        inv.setItem(P_REFRESH, Menus.item(Material.CLOCK, msg().itemName("gui.admin.refresh"), List.of(), 1, false));
    }

    private void numberRow(Inventory inv, int[] slots, Material icon, String key, int value, String suffix) {
        for (int i = 0; i < slots.length; i++) {
            int delta = DELTAS[i];
            if (delta == 0) {
                inv.setItem(slots[i], Menus.item(icon,
                        msg().itemName("gui.admin.player." + key + ".name", "value", value + suffix),
                        msg().lore("gui.admin.player." + key + ".lore"), Math.max(1, Math.min(99, value)), false));
            } else {
                Material m = delta < 0 ? Material.RED_STAINED_GLASS_PANE : Material.LIME_STAINED_GLASS_PANE;
                String label = (delta > 0 ? "+" : "") + delta;
                inv.setItem(slots[i], Menus.item(m, msg().itemName(delta < 0 ? "gui.admin.minus" : "gui.admin.plus",
                        "amount", label), List.of(), Math.abs(delta), false));
            }
        }
    }

    private static int deltaAt(int[] slots, int slot) {
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == slot) return DELTAS[i];
        }
        return Integer.MIN_VALUE;
    }

    /** 套用修改：在線直接改快取；離線經 I/O 執行緒修改後更新副本。 */
    private void modify(Player admin, MenuHolder h, Consumer<PlayerData> change) {
        PlayerData cached = plugin.data().get(h.target());
        if (cached != null) {
            change.accept(cached);
            cached.dirty = true;
            quest().recomputeMask(cached);
            renderPlayer(h);
            return;
        }
        Inventory inv = h.getInventory();
        plugin.data().modifyOffline(h.target(), change, fresh -> {
            if (plugin.data().get(h.target()) == null) {
                h.snapshot(fresh);
            }
            if (admin.isOnline() && admin.getOpenInventory().getTopInventory() == inv) {
                renderPlayer(h);
            }
        });
    }

    private static int clamp(long v) {
        return (int) Math.max(0, Math.min(MAX_VALUE, v));
    }

    // ------------------------------------------------------------ 今日任務

    private void openTasks(Player admin, UUID target, String name) {
        MenuHolder h = new MenuHolder(plugin, MenuHolder.Type.ADMIN_TASKS, 0, 0, 54,
                msg().get("gui.admin.tasks.title", "player", name));
        h.target(target, name);
        renderTasks(h);
        admin.openInventory(h.getInventory());
    }

    private void renderTasks(MenuHolder h) {
        Inventory inv = h.getInventory();
        inv.clear();
        menus.fill(inv);
        PlayerData d = plugin.data().get(h.target());
        if (d != null) {
            for (int i = 0; i < d.tasks.size() && i < TASK_SLOTS.length; i++) {
                DailyTask t = d.tasks.get(i);
                TaskDef def = defs().task(t.key);
                String state = t.claimed ? "gui.admin.tasks.state-claimed" : t.completed ? "gui.admin.tasks.state-done" : "gui.admin.tasks.state-active";
                List<Component> lore = msg().lore("gui.admin.tasks.lore",
                        "progress", String.valueOf(t.progress),
                        "amount", def == null ? "?" : String.valueOf(def.amount()),
                        "state", msg().text(state),
                        "key", t.key);
                if (!t.completed && def != null) {
                    lore = new ArrayList<>(lore);
                    lore.addAll(msg().lore("gui.admin.tasks.click"));
                }
                inv.setItem(TASK_SLOTS[i], Menus.item(t.completed ? Material.LIME_DYE : def == null ? Material.BARRIER : def.icon(),
                        msg().itemName("gui.admin.tasks.name", "index", String.valueOf(i + 1),
                                "task", def == null ? t.key : quest().taskName(def)), lore, 1, t.completed));
            }
        }
        inv.setItem(T_BACK, Menus.item(Material.OAK_DOOR, msg().itemName("gui.admin.back"), List.of(), 1, false));
    }

    // ------------------------------------------------------------ 點擊

    public void click(Player p, MenuHolder h, int slot) {
        if (!allowed(p)) {
            p.closeInventory();
            msg().send(p, "general.no-permission");
            return;
        }
        switch (h.type()) {
            case ADMIN_LIST -> clickList(p, h, slot);
            case ADMIN_PLAYER -> clickPlayer(p, h, slot);
            case ADMIN_TASKS -> clickTasks(p, h, slot);
            default -> { }
        }
    }

    private void clickList(Player p, MenuHolder h, int slot) {
        if (slot == L_PREV || slot == L_NEXT) {
            h.page(h.page() + (slot == L_PREV ? -1 : 1));
            renderList(h);
            return;
        }
        if (slot < 0 || slot >= PER_PAGE) return;
        List<Player> players = onlineSorted();
        int idx = h.page() * PER_PAGE + slot;
        if (idx >= players.size()) return;
        Player t = players.get(idx);
        openPlayer(p, t.getUniqueId(), t.getName());
    }

    private void clickPlayer(Player p, MenuHolder h, int slot) {
        PlayerData d = dataOf(h);
        if (slot == P_BACK) {
            openList(p, 0);
            return;
        }
        if (slot == P_REFRESH) {
            if (d == null || h.snapshot() != null) {
                openPlayer(p, h.target(), h.targetName()); // 離線：重新讀取
            } else {
                renderPlayer(h);
            }
            return;
        }
        if (d == null) return;
        Player online = Bukkit.getPlayer(h.target());
        switch (slot) {
            case P_TIER_PREV, P_TIER_NEXT -> {
                Tier cur = defs().tier(d.tierId);
                if (cur == null) cur = defs().firstTier();
                int idx = cur.index() + (slot == P_TIER_PREV ? -1 : 1);
                if (idx < 0 || idx >= defs().tiers.size()) return;
                Tier to = defs().tiers.get(idx);
                modify(p, h, x -> quest().adminSetTier(x, to));
                msg().send(p, "admin.set-tier", "player", h.targetName(), "tier", to.display());
                return;
            }
            case P_RESET -> {
                if (online == null) {
                    msg().send(p, "general.player-not-online", "player", h.targetName());
                    return;
                }
                quest().adminReset(d);
                msg().send(p, "admin.reset", "player", h.targetName());
                renderPlayer(h);
                return;
            }
            case P_TASKS -> {
                if (online == null) {
                    msg().send(p, "general.player-not-online", "player", h.targetName());
                    return;
                }
                openTasks(p, h.target(), h.targetName());
                return;
            }
            default -> { }
        }
        int delta;
        if ((delta = deltaAt(P_STAMPS, slot)) != Integer.MIN_VALUE && delta != 0) {
            int dl = delta;
            modify(p, h, x -> quest().adminSetStamps(x, clamp((long) x.stamps + dl)));
        } else if ((delta = deltaAt(P_STREAK, slot)) != Integer.MIN_VALUE && delta != 0) {
            int dl = delta;
            modify(p, h, x -> quest().adminSetStreak(x, clamp((long) x.streak + dl)));
        } else if ((delta = deltaAt(P_TICKETS, slot)) != Integer.MIN_VALUE && delta != 0) {
            int dl = delta;
            modify(p, h, x -> {
                x.tickets = clamp((long) x.tickets + dl);
                x.dirty = true;
            });
        }
    }

    private void clickTasks(Player p, MenuHolder h, int slot) {
        if (slot == T_BACK) {
            openPlayer(p, h.target(), h.targetName());
            return;
        }
        Player online = Bukkit.getPlayer(h.target());
        PlayerData d = plugin.data().get(h.target());
        if (online == null || d == null) {
            msg().send(p, "general.player-not-online", "player", h.targetName());
            return;
        }
        for (int i = 0; i < TASK_SLOTS.length && i < d.tasks.size(); i++) {
            if (TASK_SLOTS[i] != slot) continue;
            if (quest().adminComplete(online, d, i)) {
                msg().send(p, "admin.completed", "player", h.targetName(), "index", String.valueOf(i + 1));
            }
            renderTasks(h);
            return;
        }
    }
}
