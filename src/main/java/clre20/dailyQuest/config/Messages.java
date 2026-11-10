package clre20.dailyQuest.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.List;

/**
 * messages.yml。支援 &amp; 顏色碼與 &amp;#rrggbb 十六進位顏色，變數格式為 %name%。
 * 找不到的鍵會改讀 jar 內預設值，再找不到就顯示鍵名，不會拋出例外。
 */
public final class Messages {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&').hexColors().hexCharacter('#').build();

    private final YamlConfiguration file;
    private final YamlConfiguration defaults;

    public Messages(YamlConfiguration file, YamlConfiguration defaults) {
        this.file = file;
        this.defaults = defaults;
    }

    public String raw(String key) {
        String s = file.getString(key);
        if (s == null) {
            s = defaults.getString(key, key);
        }
        return s;
    }

    public List<String> rawList(String key) {
        if (file.isList(key)) {
            return file.getStringList(key);
        }
        if (defaults.isList(key)) {
            return defaults.getStringList(key);
        }
        String single = file.getString(key, defaults.getString(key));
        return single == null ? List.of(key) : List.of(single);
    }

    public static String apply(String s, String... kv) {
        if (kv.length == 0 || s.indexOf('%') < 0) {
            return s;
        }
        for (int i = 0; i + 1 < kv.length; i += 2) {
            s = s.replace("%" + kv[i] + "%", kv[i + 1] == null ? "" : kv[i + 1]);
        }
        return s;
    }

    public static Component color(String s) {
        return LEGACY.deserialize(s);
    }

    /** 物品名稱或 Lore 用：預設不斜體。 */
    public static Component item(String s) {
        return LEGACY.deserialize(s).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public static Component noItalic(Component c) {
        return c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public String text(String key, String... kv) {
        return apply(raw(key), kv);
    }

    public Component get(String key, String... kv) {
        return color(text(key, kv));
    }

    public Component itemName(String key, String... kv) {
        return item(text(key, kv));
    }

    public List<Component> lore(String key, String... kv) {
        List<String> lines = rawList(key);
        List<Component> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            out.add(item(apply(line, kv)));
        }
        return out;
    }

    /** 加上前綴後送出；內容是空字串時不送。 */
    public void send(CommandSender to, String key, String... kv) {
        String body = text(key, kv);
        if (body.isEmpty()) {
            return;
        }
        to.sendMessage(color(raw("prefix") + body));
    }

    public void sendList(CommandSender to, String key, String... kv) {
        for (String line : rawList(key)) {
            to.sendMessage(color(apply(line, kv)));
        }
    }
}
