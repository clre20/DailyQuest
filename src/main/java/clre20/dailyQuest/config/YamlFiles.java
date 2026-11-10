package clre20.dailyQuest.config;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;

/**
 * 讀取插件資料夾內的 YAML 檔。
 * <ul>
 *   <li>{@link #loadMerged}：以 jar 內的預設檔為基礎，套用使用者的值、補上缺少的項目、移除已不存在的項目，保留預設檔的註解。</li>
 *   <li>{@link #loadUserFile}：只在檔案不存在時產生預設內容（用於使用者自訂內容的檔案，例如任務池）。</li>
 * </ul>
 * 檔名一律由程式內的常數指定，不接受玩家輸入。
 */
public final class YamlFiles {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private YamlFiles() {
    }

    public static YamlConfiguration loadDefaults(JavaPlugin plugin, String name) {
        YamlConfiguration def = new YamlConfiguration();
        def.options().parseComments(true);
        try (InputStream in = plugin.getResource(name)) {
            if (in != null) {
                try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    def.load(reader);
                }
            }
        } catch (IOException | InvalidConfigurationException e) {
            plugin.getLogger().log(Level.SEVERE, "Cannot read default " + name, e);
        }
        return def;
    }

    /**
     * 讀取並自動補齊。回傳合併後的設定（一定不是 null）。
     */
    public static YamlConfiguration loadMerged(JavaPlugin plugin, String name) {
        File file = new File(plugin.getDataFolder(), name);
        YamlConfiguration defaults = loadDefaults(plugin, name);
        if (!file.exists()) {
            plugin.saveResource(name, false);
            return defaults;
        }
        YamlConfiguration user = new YamlConfiguration();
        user.options().parseComments(true);
        try {
            user.load(file);
        } catch (IOException | InvalidConfigurationException e) {
            backupBroken(plugin, file, e);
            plugin.saveResource(name, true);
            return defaults;
        }

        Set<String> defaultLeaves = new HashSet<>();
        for (String key : defaults.getKeys(true)) {
            if (!defaults.isConfigurationSection(key)) {
                defaultLeaves.add(key);
            }
        }
        boolean changed = false;
        for (String key : defaultLeaves) {
            if (key.endsWith("-version")) {
                // 版本號以預設檔為準
                if (!user.contains(key) || !String.valueOf(user.get(key)).equals(String.valueOf(defaults.get(key)))) {
                    changed = true;
                }
                continue;
            }
            if (user.contains(key) && !user.isConfigurationSection(key)) {
                defaults.set(key, user.get(key));
            } else {
                changed = true; // 缺少的項目
            }
        }
        for (String key : user.getKeys(true)) {
            if (!user.isConfigurationSection(key) && !defaultLeaves.contains(key)) {
                changed = true; // 已不存在的項目
            }
        }
        if (changed) {
            try {
                Files.copy(file.toPath(), new File(plugin.getDataFolder(), name + ".bak").toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
                defaults.save(file);
                plugin.getLogger().info("Updated " + name + " (missing entries added, backup: " + name + ".bak)");
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Cannot write " + name, e);
            }
        }
        return defaults;
    }

    /**
     * 使用者內容檔：不存在時產生預設檔；格式錯誤時備份並改用預設內容（不覆寫使用者檔案以外的東西）。
     */
    public static YamlConfiguration loadUserFile(JavaPlugin plugin, String name) {
        File file = new File(plugin.getDataFolder(), name);
        if (!file.exists()) {
            plugin.saveResource(name, false);
        }
        YamlConfiguration user = new YamlConfiguration();
        try {
            user.load(file);
            return user;
        } catch (IOException | InvalidConfigurationException e) {
            backupBroken(plugin, file, e);
            plugin.saveResource(name, true);
            return loadDefaults(plugin, name);
        }
    }

    private static void backupBroken(JavaPlugin plugin, File file, Exception e) {
        String target = file.getName() + ".broken-" + LocalDateTime.now().format(STAMP);
        try {
            Files.copy(file.toPath(), new File(plugin.getDataFolder(), target).toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // 備份失敗也繼續，至少讓插件能啟動
        }
        // 這時訊息檔可能還沒讀到，所以這條警告固定用英文
        plugin.getLogger().severe(file.getName() + " is invalid (" + e.getMessage()
                + "). Backed up as " + target + " and default values are used.");
    }
}
