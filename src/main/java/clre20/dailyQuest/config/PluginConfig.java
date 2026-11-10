package clre20.dailyQuest.config;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;

import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * config.yml 的值。每個值都有預設值與範圍限制，讀不到或不合理時改用預設值。
 */
public final class PluginConfig {

    private static final Pattern REPO = Pattern.compile("^[A-Za-z0-9-]{1,39}/[A-Za-z0-9._-]{1,100}$");

    public final ZoneId zone;
    public final LocalTime resetTime;
    public final int tasksPerDay;
    public final int rerollsPerDay;
    public final int stampThreshold;
    public final boolean autoClaim;
    public final boolean broadcastTierUp;

    public final int makeupMaxMissed;
    public final int makeupExpireDays;
    public final boolean makeupCountPatched;
    public final boolean makeupLoginNotify;
    public final int makeupLoginDelaySeconds;

    public final boolean ignoreCreative;
    public final boolean ignorePlacedBlocks;
    public final int placedCacheSize;
    public final boolean ignoreSpawnerMobs;
    public final boolean ignoreSpawnEggMobs;
    public final boolean ignoreThrownItems;

    public final Set<String> disabledWorlds;

    public final int sampleSeconds;
    public final boolean actionbar;
    public final long actionbarIntervalMs;
    public final SoundSpec soundComplete;
    public final SoundSpec soundStamp;
    public final SoundSpec soundReroll;
    public final SoundSpec soundClaim;
    public final SoundSpec soundError;

    public final boolean vault;
    public final boolean currencyEnabled;
    public final String currencyProvider;
    public final String currencyId;
    public final boolean currencyDecimal;
    public final String currencyFallbackCommand;

    public final int pendingMax;

    public final String storageType;
    public final int autosaveSeconds;
    public final int purgeInactiveDays;

    public final Material guiFiller;
    public final long clickCooldownMs;
    public final Material cellDone;
    public final Material cellDoneReward;
    public final Material cellNext;
    public final Material cellLocked;
    public final Material cellLockedReward;
    public final Material cellFinal;

    public final String updateRepository;
    public final boolean updateCheckOnStartup;
    public final boolean updateNotifyOnJoin;

    public PluginConfig(YamlConfiguration c, Logger log) {
        ZoneId z;
        try {
            z = ZoneId.of(c.getString("daily.timezone", "Asia/Taipei"));
        } catch (DateTimeException e) {
            log.warning("daily.timezone is invalid, using Asia/Taipei");
            z = ZoneId.of("Asia/Taipei");
        }
        zone = z;
        LocalTime t;
        try {
            t = LocalTime.parse(c.getString("daily.reset-time", "00:00"));
        } catch (DateTimeParseException e) {
            log.warning("daily.reset-time is invalid, using 00:00");
            t = LocalTime.MIDNIGHT;
        }
        resetTime = t.withSecond(0).withNano(0);
        tasksPerDay = clamp(c.getInt("daily.tasks-per-day", 10), 1, 28);
        rerollsPerDay = clamp(c.getInt("daily.rerolls-per-day", 5), 0, 1000);
        stampThreshold = clamp(c.getInt("daily.stamp-threshold", 5), 1, 28);
        autoClaim = c.getBoolean("daily.auto-claim", false);
        broadcastTierUp = c.getBoolean("daily.broadcast-tier-up", true);

        makeupMaxMissed = clamp(c.getInt("makeup.max-missed-days", 3), 1, 365);
        makeupExpireDays = clamp(c.getInt("makeup.expire-days", 3), 0, 365);
        makeupCountPatched = c.getBoolean("makeup.count-patched-days", true);
        makeupLoginNotify = c.getBoolean("makeup.login-notify", true);
        makeupLoginDelaySeconds = clamp(c.getInt("makeup.login-notify-delay-seconds", 3), 0, 60);

        ignoreCreative = c.getBoolean("anti-abuse.ignore-creative", true);
        ignorePlacedBlocks = c.getBoolean("anti-abuse.ignore-player-placed-blocks", true);
        placedCacheSize = clamp(c.getInt("anti-abuse.placed-block-cache-size", 50000), 100, 1_000_000);
        ignoreSpawnerMobs = c.getBoolean("anti-abuse.ignore-spawner-mobs", true);
        ignoreSpawnEggMobs = c.getBoolean("anti-abuse.ignore-spawn-egg-mobs", true);
        ignoreThrownItems = c.getBoolean("anti-abuse.ignore-thrown-items", true);

        Set<String> worlds = new HashSet<>();
        for (String w : c.getStringList("disabled-worlds")) {
            worlds.add(w.toLowerCase(Locale.ROOT));
        }
        disabledWorlds = Set.copyOf(worlds);

        sampleSeconds = clamp(c.getInt("progress.sample-seconds", 10), 1, 300);
        actionbar = c.getBoolean("progress.actionbar", true);
        actionbarIntervalMs = clamp(c.getInt("progress.actionbar-interval-ms", 1000), 0, 60000);
        soundComplete = SoundSpec.parse(c.getString("progress.sound-task-complete", ""));
        soundStamp = SoundSpec.parse(c.getString("progress.sound-stamp", ""));
        soundReroll = SoundSpec.parse(c.getString("progress.sound-reroll", ""));
        soundClaim = SoundSpec.parse(c.getString("progress.sound-claim", ""));
        soundError = SoundSpec.parse(c.getString("progress.sound-error", ""));

        vault = c.getBoolean("economy.vault", true);
        currencyEnabled = c.getBoolean("economy.second-currency.enabled", true);
        currencyProvider = c.getString("economy.second-currency.provider", "EXCELLENT_ECONOMY").toUpperCase(Locale.ROOT);
        currencyId = c.getString("economy.second-currency.currency-id", "penguin");
        currencyDecimal = c.getBoolean("economy.second-currency.decimal", false);
        currencyFallbackCommand = c.getString("economy.second-currency.fallback-command", "");

        pendingMax = clamp(c.getInt("pending-items.max", 100), 0, 1000);

        String st = c.getString("storage.type", "yaml").toLowerCase(Locale.ROOT);
        storageType = st.equals("sqlite") ? "sqlite" : "yaml";
        autosaveSeconds = clamp(c.getInt("storage.autosave-seconds", 300), 30, 3600);
        purgeInactiveDays = clamp(c.getInt("storage.purge-inactive-days", 0), 0, 3650);

        guiFiller = material(c.getString("gui.filler"), Material.GRAY_STAINED_GLASS_PANE);
        clickCooldownMs = clamp(c.getInt("gui.click-cooldown-ms", 250), 0, 5000);
        cellDone = material(c.getString("gui.cell.done"), Material.LIME_STAINED_GLASS_PANE);
        cellDoneReward = material(c.getString("gui.cell.done-reward"), Material.LIME_STAINED_GLASS);
        cellNext = material(c.getString("gui.cell.next"), Material.YELLOW_STAINED_GLASS_PANE);
        cellLocked = material(c.getString("gui.cell.locked"), Material.WHITE_STAINED_GLASS_PANE);
        cellLockedReward = material(c.getString("gui.cell.locked-reward"), Material.CHEST);
        cellFinal = material(c.getString("gui.cell.final"), Material.NETHER_STAR);

        String repo = c.getString("update-checker.repository", "clre20/DailyQuest").trim();
        if (!repo.isEmpty() && !REPO.matcher(repo).matches()) {
            log.warning("update-checker.repository is not Owner/Repo, update check disabled");
            repo = "";
        }
        updateRepository = repo;
        updateCheckOnStartup = c.getBoolean("update-checker.check-on-startup", true);
        updateNotifyOnJoin = c.getBoolean("update-checker.notify-on-join", true);
    }

    public static Material material(String name, Material fallback) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        Material m = Material.matchMaterial(name.trim());
        return m != null && m.isItem() && !m.isAir() ? m : fallback;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    /** 音效設定：「名稱 音量 音調」。 */
    public record SoundSpec(String key, float volume, float pitch) {
        static SoundSpec parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            String[] p = raw.trim().split("\\s+");
            float v = 1f;
            float pi = 1f;
            try {
                if (p.length > 1) v = Float.parseFloat(p[1]);
                if (p.length > 2) pi = Float.parseFloat(p[2]);
            } catch (NumberFormatException ignored) {
                // 用預設值
            }
            String key = p[0].toLowerCase(Locale.ROOT);
            if (!key.matches("[a-z0-9_.:/-]{1,100}")) {
                return null;
            }
            return new SoundSpec(key, Math.max(0f, Math.min(10f, v)), Math.max(0.5f, Math.min(2f, pi)));
        }
    }
}
