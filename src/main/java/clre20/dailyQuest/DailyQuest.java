package clre20.dailyQuest;

import clre20.dailyQuest.command.DqCommand;
import clre20.dailyQuest.config.Messages;
import clre20.dailyQuest.config.PluginConfig;
import clre20.dailyQuest.config.YamlFiles;
import clre20.dailyQuest.data.DataManager;
import clre20.dailyQuest.data.SqliteStorage;
import clre20.dailyQuest.data.Storage;
import clre20.dailyQuest.data.YamlStorage;
import clre20.dailyQuest.economy.Economies;
import clre20.dailyQuest.gui.AdminMenus;
import clre20.dailyQuest.gui.GuiListener;
import clre20.dailyQuest.gui.MenuHolder;
import clre20.dailyQuest.gui.Menus;
import clre20.dailyQuest.hook.PapiHook;
import clre20.dailyQuest.listener.PlacedBlocks;
import clre20.dailyQuest.listener.PlayerListener;
import clre20.dailyQuest.listener.ProgressListener;
import clre20.dailyQuest.quest.Definitions;
import clre20.dailyQuest.quest.QuestService;
import clre20.dailyQuest.quest.TaskType;
import clre20.dailyQuest.reward.RewardService;
import clre20.dailyQuest.update.UpdateChecker;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.UUID;
import java.util.logging.Level;

public final class DailyQuest extends JavaPlugin {

    private PluginConfig settings;
    private Messages messages;
    private Definitions defs;
    private Economies economies;
    private RewardService rewards;
    private DataManager data;
    private QuestService quest;
    private Menus menus;
    private AdminMenus adminMenus;
    private ProgressListener progressListener;
    private PlayerListener playerListener;
    private UpdateChecker updates;
    private Runnable papiUnregister;

    private BukkitTask tickTask;
    private BukkitTask autosaveTask;
    private BukkitTask sampleTask;

    @Override
    public void onEnable() {
        getDataFolder().mkdirs();
        loadFiles();

        Storage storage = createStorage();
        data = new DataManager(this, storage, TaskType.all().length);
        economies = new Economies(this, settings);
        rewards = new RewardService(this, economies, settings, messages);
        quest = new QuestService(data, rewards, settings, messages, defs);
        menus = new Menus(this, data, quest, rewards, settings, messages);
        adminMenus = new AdminMenus(this, menus);
        menus.setAdmin(adminMenus);
        progressListener = new ProgressListener(quest, new PlacedBlocks(settings.placedCacheSize), settings);
        playerListener = new PlayerListener(this);
        updates = new UpdateChecker(this, settings.updateRepository, messages);

        getServer().getPluginManager().registerEvents(progressListener, this);
        getServer().getPluginManager().registerEvents(playerListener, this);
        getServer().getPluginManager().registerEvents(new GuiListener(this, menus), this);

        PluginCommand cmd = getCommand("dailyquest");
        if (cmd != null) {
            DqCommand executor = new DqCommand(this);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }

        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try {
                papiUnregister = PapiHook.register(this);
                if (papiUnregister != null) {
                    getLogger().info("PlaceholderAPI placeholders registered");
                }
            } catch (LinkageError e) {
                getLogger().warning("Cannot register PlaceholderAPI placeholders: " + e);
            }
        }

        scheduleTasks();
        if (settings.purgeInactiveDays > 0) {
            data.purge(quest.today() - settings.purgeInactiveDays);
        }
        // 重載插件（例如 /reload）時，替已在線的玩家讀取資料
        for (Player p : Bukkit.getOnlinePlayers()) {
            playerListener.load(p);
        }
        if (settings.updateCheckOnStartup && !settings.updateRepository.isEmpty()) {
            updates.logStartup();
        }
        getLogger().info("Enabled: " + defs.tiers.size() + " tiers, " + defs.taskCount() + " tasks, storage "
                + settings.storageType);
    }

    @Override
    public void onDisable() {
        cancelTasks();
        // 關掉所有本插件的選單，避免關服或重載後還能操作
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder(false) instanceof MenuHolder) {
                p.closeInventory();
            }
        }
        if (papiUnregister != null) {
            papiUnregister.run();
            papiUnregister = null;
        }
        if (updates != null) {
            updates.shutdown();
        }
        if (progressListener != null) {
            progressListener.clear();
        }
        if (data != null) {
            data.shutdown();
        }
    }

    // ------------------------------------------------------------ 設定

    private void loadFiles() {
        settings = new PluginConfig(YamlFiles.loadMerged(this, "config.yml"), getLogger());
        messages = new Messages(YamlFiles.loadMerged(this, "messages.yml"), YamlFiles.loadDefaults(this, "messages.yml"));
        YamlConfiguration tiers = YamlFiles.loadUserFile(this, "tiers.yml");
        YamlConfiguration tasks = YamlFiles.loadUserFile(this, "tasks.yml");
        YamlConfiguration streak = YamlFiles.loadUserFile(this, "streak.yml");
        YamlConfiguration rewardsYml = YamlFiles.loadUserFile(this, "rewards.yml");
        defs = new Definitions(tiers, tasks, streak, rewardsYml, getLogger());
    }

    private Storage createStorage() {
        if (settings.storageType.equals("sqlite")) {
            SqliteStorage s = new SqliteStorage(getDataFolder().toPath());
            try {
                s.init();
                return s;
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "Cannot open SQLite database, falling back to YAML", e);
                s.close();
            }
        }
        YamlStorage y = new YamlStorage(getDataFolder().toPath(), settings.zone);
        try {
            y.init();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Cannot create data folder", e);
        }
        return y;
    }

    /** /dq reload：重新讀取所有設定檔（儲存方式要重開服才會換）。 */
    public void reloadAll() {
        String oldStorage = settings.storageType;
        loadFiles();
        if (!oldStorage.equals(settings.storageType)) {
            getLogger().warning("storage.type changed, restart the server to apply it");
        }
        economies.reload(settings);
        rewards.reload(settings, messages);
        quest.reload(settings, messages, defs);
        menus.reload(settings, messages);
        progressListener.reload(settings);
        updates.reload(settings.updateRepository, messages);
        // 關掉開著的選單，避免顯示舊內容
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder(false) instanceof MenuHolder) {
                p.closeInventory();
            }
        }
        cancelTasks();
        scheduleTasks();
    }

    private void scheduleTasks() {
        tickTask = Bukkit.getScheduler().runTaskTimer(this, quest::tick, 20L, 20L);
        long save = settings.autosaveSeconds * 20L;
        autosaveTask = Bukkit.getScheduler().runTaskTimer(this, data::saveDirty, save, save);
        long sample = settings.sampleSeconds * 20L;
        sampleTask = Bukkit.getScheduler().runTaskTimer(this, this::sampleAll, sample, sample);
    }

    private void cancelTasks() {
        for (BukkitTask t : new BukkitTask[]{tickTask, autosaveTask, sampleTask}) {
            if (t != null) t.cancel();
        }
        tickTask = autosaveTask = sampleTask = null;
    }

    private void sampleAll() {
        if (quest.sampledPlayers().isEmpty()) return;
        for (UUID id : new ArrayList<>(quest.sampledPlayers())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) quest.sample(p);
        }
    }

    // ------------------------------------------------------------ 存取

    public PluginConfig settings() {
        return settings;
    }

    public Messages messages() {
        return messages;
    }

    public DataManager data() {
        return data;
    }

    public QuestService quest() {
        return quest;
    }

    public Menus menus() {
        return menus;
    }

    public AdminMenus adminMenus() {
        return adminMenus;
    }

    public UpdateChecker updates() {
        return updates;
    }
}
