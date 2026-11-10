package clre20.dailyQuest.reward;

import clre20.dailyQuest.config.Messages;
import clre20.dailyQuest.config.PluginConfig;
import clre20.dailyQuest.data.PlayerData;
import clre20.dailyQuest.economy.Economies;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * 發放獎勵與產生獎勵說明。物品放不下時進入待領取清單，清單滿了才掉在腳下。
 */
public final class RewardService {

    private final JavaPlugin plugin;
    private final Economies eco;
    private PluginConfig cfg;
    private Messages msg;

    public RewardService(JavaPlugin plugin, Economies eco, PluginConfig cfg, Messages msg) {
        this.plugin = plugin;
        this.eco = eco;
        this.cfg = cfg;
        this.msg = msg;
    }

    public void reload(PluginConfig cfg, Messages msg) {
        this.cfg = cfg;
        this.msg = msg;
    }

    /**
     * 發放獎勵並在聊天室列出內容。
     *
     * @param titleKey 聊天室標題的訊息鍵（例如 reward.title-task）
     */
    public void give(Player p, PlayerData d, RewardSet r, String titleKey, String... kv) {
        if (r == null || r.isEmpty()) {
            return;
        }
        if (r.money > 0) {
            eco.depositMoney(p, r.money);
        }
        if (r.currency > 0) {
            eco.depositCurrency(p, r.currency);
        }
        if (r.tickets > 0) {
            d.tickets = (int) Math.min(Integer.MAX_VALUE, (long) d.tickets + r.tickets);
        }
        if (!r.items.isEmpty()) {
            boolean stored = false;
            for (ItemStack template : r.items) {
                Map<Integer, ItemStack> left = p.getInventory().addItem(template.clone());
                for (ItemStack rest : left.values()) {
                    if (d.pending.size() < cfg.pendingMax) {
                        d.pending.add(Base64.getEncoder().encodeToString(rest.serializeAsBytes()));
                        stored = true;
                    } else {
                        p.getWorld().dropItemNaturally(p.getLocation(), rest);
                    }
                }
            }
            if (stored) {
                msg.send(p, "reward.inventory-full", "count", String.valueOf(d.pending.size()));
            }
        }
        for (String c : r.commands) {
            String line = c.replace("%player%", p.getName()).replace("%uuid%", p.getUniqueId().toString());
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "Reward command failed: " + line, e);
            }
        }
        d.dirty = true;
        msg.send(p, titleKey, kv);
        for (Component line : describe(r)) {
            p.sendMessage(line);
        }
    }

    /** 領取待領取物品，回傳成功放進背包的數量。 */
    public int claimPending(Player p, PlayerData d) {
        int given = 0;
        Iterator<String> it = d.pending.iterator();
        while (it.hasNext()) {
            String b64 = it.next();
            ItemStack item;
            try {
                item = ItemStack.deserializeBytes(Base64.getDecoder().decode(b64));
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Removed an unreadable pending item of " + p.getName());
                it.remove();
                d.dirty = true;
                continue;
            }
            Map<Integer, ItemStack> left = p.getInventory().addItem(item);
            it.remove();
            d.dirty = true;
            if (left.isEmpty()) {
                given++;
                continue;
            }
            // 放不下的部分放回清單，停止領取
            for (ItemStack rest : left.values()) {
                d.pending.add(Base64.getEncoder().encodeToString(rest.serializeAsBytes()));
            }
            break;
        }
        return given;
    }

    /** 獎勵說明（Lore 與聊天室共用）。 */
    public List<Component> describe(RewardSet r) {
        List<Component> out = new ArrayList<>();
        if (r == null) {
            return out;
        }
        if (r.display != null) {
            for (String s : r.display) {
                out.add(Messages.item(s));
            }
            return out;
        }
        if (r.money > 0) {
            out.add(msg.itemName("reward.line-money", "amount", eco.formatMoney(r.money)));
        }
        if (r.currency > 0 && eco.currencyEnabled()) {
            out.add(msg.itemName("reward.line-currency", "amount", Economies.plain(eco.normalizeCurrency(r.currency))));
        }
        if (r.tickets > 0) {
            out.add(msg.itemName("reward.line-tickets", "amount", String.valueOf(r.tickets)));
        }
        for (ItemStack it : r.items) {
            Component name = itemName(it);
            Component line = msg.itemName("reward.line-item", "amount", String.valueOf(it.getAmount()))
                    .replaceText(TextReplacementConfig.builder().matchLiteral("%item%").replacement(name).build());
            out.add(line);
        }
        if (!r.commands.isEmpty()) {
            String extra = msg.raw("reward.line-command");
            if (!extra.isEmpty()) {
                out.add(Messages.item(extra));
            }
        }
        return out;
    }

    private static Component itemName(ItemStack it) {
        ItemMeta meta = it.getItemMeta();
        if (meta != null && meta.hasCustomName()) {
            Component c = meta.customName();
            if (c != null) {
                return c;
            }
        }
        if (meta != null && meta.hasItemName()) {
            return meta.itemName();
        }
        return Component.translatable(it.translationKey());
    }
}
