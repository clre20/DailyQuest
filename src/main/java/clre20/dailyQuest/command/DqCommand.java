package clre20.dailyQuest.command;

import clre20.dailyQuest.DailyQuest;
import clre20.dailyQuest.config.Messages;
import clre20.dailyQuest.data.DailyTask;
import clre20.dailyQuest.data.PlayerData;
import clre20.dailyQuest.quest.QuestService;
import clre20.dailyQuest.quest.TaskDef;
import clre20.dailyQuest.quest.Tier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * /dailyquest（/dq）。每個子指令都在執行前檢查權限；Tab 補全只列出有權限的項目。
 */
public final class DqCommand implements TabExecutor {

    public static final String P_USE = "dailyquest.use";
    public static final String P_HELP = "dailyquest.help";
    public static final String P_VERSION = "dailyquest.version";
    public static final String P_RELOAD = "dailyquest.admin.reload";
    public static final String P_MANAGE = "dailyquest.admin.manage";

    private static final int MAX_NUMBER = 1_000_000;
    private static final List<String> ADMIN_SUBS = List.of("info", "reset", "complete", "settier", "setstamps",
            "setstreak", "ticket", "itemstring");

    private final DailyQuest plugin;

    public DqCommand(DailyQuest plugin) {
        this.plugin = plugin;
    }

    private Messages msg() {
        return plugin.messages();
    }

    private QuestService quest() {
        return plugin.quest();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "" -> {
                if (sender instanceof Player p) {
                    if (check(sender, P_USE) && notSpamming(p)) plugin.menus().openMain(p);
                } else {
                    help(sender, label);
                }
            }
            case "help" -> {
                if (check(sender, P_HELP)) help(sender, label);
            }
            case "map", "streak" -> {
                if (!(sender instanceof Player p)) {
                    msg().send(sender, "general.player-only");
                } else if (check(sender, P_USE) && notSpamming(p)) {
                    if (sub.equals("map")) plugin.menus().openMap(p);
                    else plugin.menus().openStreak(p);
                }
            }
            case "version" -> {
                if (check(sender, P_VERSION)) plugin.updates().checkCommand(sender);
            }
            case "reload" -> {
                if (check(sender, P_RELOAD)) {
                    plugin.reloadAll();
                    msg().send(sender, "general.reloaded", "tasks", String.valueOf(quest().defs().taskCount()),
                            "tiers", String.valueOf(quest().defs().tiers.size()));
                }
            }
            case "admin" -> {
                if (check(sender, P_MANAGE)) admin(sender, label, args);
            }
            default -> msg().send(sender, "general.unknown-command", "label", label);
        }
        return true;
    }

    /** 開選單的指令也套用點擊冷卻，避免狂按造成負擔。 */
    private boolean notSpamming(Player p) {
        PlayerData d = plugin.data().get(p.getUniqueId());
        if (d == null) {
            msg().send(p, "general.loading");
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - d.lastCommand < Math.max(250L, plugin.settings().clickCooldownMs)) {
            return false;
        }
        d.lastCommand = now;
        return true;
    }

    private boolean check(CommandSender s, String perm) {
        if (s.hasPermission(perm)) {
            return true;
        }
        msg().send(s, "general.no-permission");
        return false;
    }

    private void help(CommandSender s, String label) {
        msg().sendList(s, "help.header");
        if (s.hasPermission(P_USE)) msg().sendList(s, "help.use", "label", label);
        if (s.hasPermission(P_HELP)) msg().sendList(s, "help.help", "label", label);
        if (s.hasPermission(P_VERSION)) msg().sendList(s, "help.version", "label", label);
        if (s.hasPermission(P_RELOAD)) msg().sendList(s, "help.reload", "label", label);
        if (s.hasPermission(P_MANAGE)) msg().sendList(s, "help.admin", "label", label);
    }

    // ------------------------------------------------------------ admin

    private void admin(CommandSender s, String label, String[] args) {
        if (args.length < 2) {
            msg().sendList(s, "help.admin", "label", label);
            return;
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        if (sub.equals("itemstring")) {
            itemString(s);
            return;
        }
        if (!ADMIN_SUBS.contains(sub) || args.length < 3) {
            msg().sendList(s, "help.admin", "label", label);
            return;
        }
        // ticket 的格式是 ticket <give|take|set> <玩家> <數量>，其他都是 <子指令> <玩家> ...
        String name = sub.equals("ticket") ? (args.length > 3 ? args[3] : "") : args[2];
        switch (sub) {
            case "info" -> withData(s, name, d -> info(s, d));
            case "reset" -> withOnline(s, name, (p, d) -> {
                quest().adminReset(d);
                msg().send(s, "admin.reset", "player", p.getName());
            });
            case "complete" -> {
                Integer n = number(s, args, 3, 1, 28);
                if (n == null) return;
                withOnline(s, name, (p, d) -> {
                    if (quest().adminComplete(p, d, n - 1)) {
                        msg().send(s, "admin.completed", "player", p.getName(), "index", String.valueOf(n));
                    } else {
                        msg().send(s, "admin.complete-failed", "index", String.valueOf(n));
                    }
                });
            }
            case "settier" -> {
                if (args.length < 4) {
                    msg().sendList(s, "help.admin", "label", label);
                    return;
                }
                Tier tier = quest().defs().tier(args[3]);
                if (tier == null) {
                    msg().send(s, "admin.unknown-tier", "tier", args[3]);
                    return;
                }
                modify(s, name, d -> quest().adminSetTier(d, tier), () ->
                        msg().send(s, "admin.set-tier", "player", name, "tier", tier.display()));
            }
            case "setstamps" -> {
                Integer n = number(s, args, 3, 0, MAX_NUMBER);
                if (n == null) return;
                modify(s, name, d -> quest().adminSetStamps(d, n), () ->
                        msg().send(s, "admin.set-stamps", "player", name, "value", String.valueOf(n)));
            }
            case "setstreak" -> {
                Integer n = number(s, args, 3, 0, MAX_NUMBER);
                if (n == null) return;
                modify(s, name, d -> quest().adminSetStreak(d, n), () ->
                        msg().send(s, "admin.set-streak", "player", name, "value", String.valueOf(n)));
            }
            case "ticket" -> {
                if (args.length < 4) {
                    msg().sendList(s, "help.admin", "label", label);
                    return;
                }
                String op = args[2].toLowerCase(Locale.ROOT);
                if (!op.equals("give") && !op.equals("take") && !op.equals("set")) {
                    msg().sendList(s, "help.admin", "label", label);
                    return;
                }
                Integer n = number(s, args, 4, 0, MAX_NUMBER);
                if (n == null) return;
                modify(s, name, d -> {
                    long v = switch (op) {
                        case "give" -> (long) d.tickets + n;
                        case "take" -> (long) d.tickets - n;
                        default -> n;
                    };
                    d.tickets = (int) Math.max(0, Math.min(MAX_NUMBER, v));
                    d.dirty = true;
                }, () -> msg().send(s, "admin.ticket-" + op, "player", name, "amount", String.valueOf(n)));
            }
            default -> msg().sendList(s, "help.admin", "label", label);
        }
    }

    private void info(CommandSender s, PlayerData d) {
        Tier tier = quest().defs().tier(d.tierId);
        msg().sendList(s, "admin.info",
                "player", d.name.isEmpty() ? d.uuid.toString() : d.name,
                "tier", tier == null ? String.valueOf(d.tierId) : tier.display(),
                "stamps", String.valueOf(d.stamps),
                "cells", tier == null ? "?" : String.valueOf(tier.cells()),
                "streak", String.valueOf(d.streak),
                "best", String.valueOf(d.bestStreak),
                "tickets", String.valueOf(d.tickets),
                "done", String.valueOf(d.completedCount()),
                "total", String.valueOf(d.tasks.size()),
                "rerolls", String.valueOf(quest().rerollsLeft(d)),
                "pending", String.valueOf(d.pending.size()),
                "broken", d.brokenPrev > 0 ? String.valueOf(d.brokenPrev) : "-");
        if (d.day == quest().today()) {
            int i = 1;
            for (DailyTask t : d.tasks) {
                TaskDef def = quest().defs().task(t.key);
                msg().sendList(s, "admin.info-task", "index", String.valueOf(i++),
                        "task", def == null ? t.key : quest().taskName(def),
                        "progress", String.valueOf(t.progress),
                        "amount", def == null ? "?" : String.valueOf(def.amount()),
                        "state", msg().text(t.claimed ? "admin.state-claimed" : t.completed ? "admin.state-done" : "admin.state-active"));
            }
        }
    }

    private void itemString(CommandSender s) {
        if (!(s instanceof Player p)) {
            msg().send(s, "general.player-only");
            return;
        }
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            msg().send(s, "admin.itemstring-empty");
            return;
        }
        ItemMeta meta = hand.getItemMeta();
        String str = hand.getType().getKey().toString() + (meta == null ? "" : meta.getAsComponentString());
        if (str.length() > 30000) {
            msg().send(s, "admin.itemstring-too-long");
            return;
        }
        // 以 YAML 單引號字串輸出，可直接貼進設定檔
        String yaml = "'" + str.replace("'", "''") + "'";
        p.sendMessage(msg().get("admin.itemstring")
                .append(Component.text(yaml)
                        .clickEvent(ClickEvent.copyToClipboard(yaml))
                        .hoverEvent(HoverEvent.showText(msg().get("admin.itemstring-hover")))));
    }

    // ------------------------------------------------------------ 工具

    private Integer number(CommandSender s, String[] args, int idx, int min, int max) {
        if (args.length <= idx) {
            msg().send(s, "general.invalid-number", "min", String.valueOf(min), "max", String.valueOf(max));
            return null;
        }
        try {
            int n = Integer.parseInt(args[idx]);
            if (n < min || n > max) throw new NumberFormatException();
            return n;
        } catch (NumberFormatException e) {
            msg().send(s, "general.invalid-number", "min", String.valueOf(min), "max", String.valueOf(max));
            return null;
        }
    }

    /** 只從伺服器快取找玩家，不會向 Mojang 查詢。 */
    private UUID resolve(CommandSender s, String name) {
        if (!name.matches("[A-Za-z0-9_.*-]{1,32}")) {
            msg().send(s, "general.player-not-found", "player", name);
            return null;
        }
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online.getUniqueId();
        OfflinePlayer off = Bukkit.getOfflinePlayerIfCached(name);
        if (off == null || !off.hasPlayedBefore()) {
            msg().send(s, "general.player-not-found", "player", name);
            return null;
        }
        return off.getUniqueId();
    }

    private void withOnline(CommandSender s, String name, java.util.function.BiConsumer<Player, PlayerData> action) {
        Player p = Bukkit.getPlayerExact(name);
        PlayerData d = p == null ? null : plugin.data().get(p.getUniqueId());
        if (p == null || d == null) {
            msg().send(s, "general.player-not-online", "player", name);
            return;
        }
        action.accept(p, d);
    }

    private void withData(CommandSender s, String name, Consumer<PlayerData> action) {
        UUID uuid = resolve(s, name);
        if (uuid == null) return;
        PlayerData cached = plugin.data().get(uuid);
        if (cached != null) {
            action.accept(cached);
            return;
        }
        plugin.data().load(uuid, action);
    }

    private void modify(CommandSender s, String name, Consumer<PlayerData> change, Runnable done) {
        UUID uuid = resolve(s, name);
        if (uuid == null) return;
        PlayerData cached = plugin.data().get(uuid);
        if (cached != null) {
            change.accept(cached);
            cached.dirty = true;
            quest().recomputeMask(cached);
            done.run();
            return;
        }
        plugin.data().modifyOffline(uuid, change, done);
    }

    // ------------------------------------------------------------ Tab

    @Override
    public List<String> onTabComplete(CommandSender s, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            if (s.hasPermission(P_USE)) { out.add("map"); out.add("streak"); }
            if (s.hasPermission(P_HELP)) out.add("help");
            if (s.hasPermission(P_VERSION)) out.add("version");
            if (s.hasPermission(P_RELOAD)) out.add("reload");
            if (s.hasPermission(P_MANAGE)) out.add("admin");
            return filter(out, args[0]);
        }
        if (!args[0].equalsIgnoreCase("admin") || !s.hasPermission(P_MANAGE)) {
            return out;
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            return filter(new ArrayList<>(ADMIN_SUBS), args[1]);
        }
        if (sub.equals("itemstring")) {
            return out;
        }
        boolean ticket = sub.equals("ticket");
        if (args.length == 3) {
            if (ticket) {
                out.addAll(List.of("give", "take", "set"));
            } else {
                for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
            }
            return filter(out, args[2]);
        }
        if (args.length == 4) {
            switch (sub) {
                case "settier" -> {
                    for (Tier t : quest().defs().tiers) out.add(t.id());
                }
                case "ticket" -> {
                    for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
                }
                case "complete" -> {
                    for (int i = 1; i <= plugin.settings().tasksPerDay; i++) out.add(String.valueOf(i));
                }
                case "setstamps", "setstreak" -> out.add("0");
                default -> { }
            }
            return filter(out, args[3]);
        }
        if (args.length == 5 && sub.equals("ticket")) {
            out.addAll(List.of("1", "5", "10"));
            return filter(out, args[4]);
        }
        return out;
    }

    private static List<String> filter(List<String> list, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String s : list) {
            if (s.toLowerCase(Locale.ROOT).startsWith(p)) out.add(s);
        }
        return out;
    }
}
