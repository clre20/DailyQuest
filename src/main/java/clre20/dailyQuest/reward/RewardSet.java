package clre20.dailyQuest.reward;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 一組獎勵：錢、第二貨幣、補簽卷、物品、指令，可以混搭。
 * 物品在讀取設定時就解析好，發放時只複製。
 */
public final class RewardSet {

    public static final RewardSet EMPTY = new RewardSet(0, 0, 0, List.of(), List.of(), null);

    private static final int MAX_ITEMS = 54;
    private static final int MAX_COMMANDS = 20;

    public final double money;
    public final double currency;
    public final int tickets;
    public final List<ItemStack> items;
    public final List<String> commands;
    /** 自訂說明；null＝自動產生。 */
    public final List<String> display;

    private RewardSet(double money, double currency, int tickets, List<ItemStack> items,
                      List<String> commands, List<String> display) {
        this.money = money;
        this.currency = currency;
        this.tickets = tickets;
        this.items = items;
        this.commands = commands;
        this.display = display;
    }

    public boolean isEmpty() {
        return money <= 0 && currency <= 0 && tickets <= 0 && items.isEmpty() && commands.isEmpty();
    }

    public static RewardSet parse(ConfigurationSection s, String where, Logger log) {
        if (s == null) {
            return EMPTY;
        }
        double money = Math.max(0, Math.min(1e12, s.getDouble("money", 0)));
        double currency = Math.max(0, Math.min(1e12, s.getDouble("currency", 0)));
        int tickets = Math.max(0, Math.min(10000, s.getInt("tickets", 0)));
        List<ItemStack> items = new ArrayList<>();
        List<?> rawItems = s.getList("items");
        if (rawItems != null) {
            for (Object o : rawItems) {
                if (items.size() >= MAX_ITEMS) {
                    log.warning(where + ": more than " + MAX_ITEMS + " items, the rest are ignored");
                    break;
                }
                ItemStack it = parseItem(o, where, log);
                if (it != null) {
                    items.add(it);
                }
            }
        }
        List<String> commands = new ArrayList<>();
        for (String c : s.getStringList("commands")) {
            if (commands.size() >= MAX_COMMANDS) {
                break;
            }
            String cmd = c.startsWith("/") ? c.substring(1) : c;
            if (!cmd.isBlank()) {
                commands.add(cmd);
            }
        }
        List<String> display = s.isList("display") ? List.copyOf(s.getStringList("display")) : null;
        if (money == 0 && currency == 0 && tickets == 0 && items.isEmpty() && commands.isEmpty()) {
            return display == null ? EMPTY : new RewardSet(0, 0, 0, List.of(), List.of(), display);
        }
        return new RewardSet(money, currency, tickets, List.copyOf(items), List.copyOf(commands), display);
    }

    private static ItemStack parseItem(Object o, String where, Logger log) {
        String spec;
        int amount = 1;
        if (o instanceof Map<?, ?> map) {
            Object item = map.get("item");
            if (item == null) {
                log.warning(where + ": item entry without 'item'");
                return null;
            }
            spec = String.valueOf(item).trim();
            Object a = map.get("amount");
            if (a instanceof Number n) {
                amount = n.intValue();
            } else if (a != null) {
                try {
                    amount = Integer.parseInt(String.valueOf(a).trim());
                } catch (NumberFormatException e) {
                    amount = 1;
                }
            }
        } else if (o != null) {
            spec = String.valueOf(o).trim();
            int sp = spec.lastIndexOf(' ');
            if (sp > 0) {
                try {
                    amount = Integer.parseInt(spec.substring(sp + 1));
                    spec = spec.substring(0, sp).trim();
                } catch (NumberFormatException ignored) {
                    // 沒有數量
                }
            }
        } else {
            return null;
        }
        if (spec.isEmpty() || spec.length() > 32767) {
            return null;
        }
        ItemStack stack = null;
        if (spec.indexOf('[') < 0 && spec.indexOf('{') < 0) {
            Material m = Material.matchMaterial(spec);
            if (m != null && m.isItem() && !m.isAir()) {
                stack = new ItemStack(m);
            }
        }
        if (stack == null) {
            try {
                stack = Bukkit.getItemFactory().createItemStack(spec);
            } catch (IllegalArgumentException e) {
                log.warning(where + ": invalid item '" + shorten(spec) + "' (" + e.getMessage() + ")");
                return null;
            }
        }
        if (stack.getType().isAir()) {
            return null;
        }
        stack.setAmount(Math.max(1, Math.min(amount, 6400)));
        return stack;
    }

    private static String shorten(String s) {
        return s.length() > 80 ? s.substring(0, 80) + "..." : s;
    }
}
