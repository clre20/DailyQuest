package clre20.dailyQuest.quest;

import clre20.dailyQuest.config.PluginConfig;
import clre20.dailyQuest.reward.RewardSet;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Tag;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * 階級、任務池、連勝路線與其他獎勵。讀取後不再變動（重載時整個換掉）。
 */
public final class Definitions {

    private static final int MAX_TIERS = 200;
    private static final int MAX_TASKS_PER_TIER = 500;

    public final List<Tier> tiers;
    private final Map<String, Tier> tierById;
    private final Map<String, TaskDef> tasks;
    private final Map<String, List<TaskDef>> pools;
    /** 有出現在 BREAK_BLOCK / HARVEST 任務的材質；null＝有任務不限材質（要記錄所有放置）。 */
    public final Set<Material> trackedPlaceMaterials;
    public final StreakTrack streak;
    public final RewardSet allComplete;
    public final RewardSet everyStamp;

    public Definitions(YamlConfiguration tiersYml, YamlConfiguration tasksYml, YamlConfiguration streakYml,
                       YamlConfiguration rewardsYml, Logger log) {
        // ---- 階級 ----
        List<Tier> list = new ArrayList<>();
        Map<String, Tier> byId = new HashMap<>();
        ConfigurationSection ts = tiersYml.getConfigurationSection("tiers");
        if (ts != null) {
            for (String id : ts.getKeys(false)) {
                if (list.size() >= MAX_TIERS) {
                    break;
                }
                ConfigurationSection s = ts.getConfigurationSection(id);
                if (s == null || !validId(id)) {
                    log.warning("tiers.yml: invalid tier '" + id + "'");
                    continue;
                }
                String where = "tiers.yml " + id;
                int cells = Math.max(1, Math.min(10000, s.getInt("cells", 20)));
                Map<Integer, RewardSet> cellRewards = new HashMap<>();
                ConfigurationSection cr = s.getConfigurationSection("cell-rewards");
                if (cr != null) {
                    for (String k : cr.getKeys(false)) {
                        Integer cell = parseInt(k);
                        if (cell == null || cell < 1 || cell > cells) {
                            log.warning(where + ": cell-rewards '" + k + "' is not between 1 and " + cells);
                            continue;
                        }
                        cellRewards.put(cell, RewardSet.parse(cr.getConfigurationSection(k), where + " cell " + k, log));
                    }
                }
                Tier t = new Tier(id, list.size(), s.getString("display", id),
                        PluginConfig.material(s.getString("icon"), Material.PAPER), cells,
                        RewardSet.parse(s.getConfigurationSection("task-reward"), where + " task-reward", log),
                        Map.copyOf(cellRewards));
                list.add(t);
                byId.put(id, t);
            }
        }
        if (list.isEmpty()) {
            log.severe("tiers.yml has no valid tier, a default one is used");
            Tier t = new Tier("default", 0, "&fDefault", Material.PAPER, 20, RewardSet.EMPTY, Map.of());
            list.add(t);
            byId.put(t.id(), t);
        }
        tiers = List.copyOf(list);
        tierById = Map.copyOf(byId);

        // ---- 任務池 ----
        Map<String, TaskDef> all = new HashMap<>();
        Map<String, List<TaskDef>> byTier = new HashMap<>();
        Set<Material> placeTracked = new HashSet<>();
        boolean trackAll = false;
        for (String tierId : tasksYml.getKeys(false)) {
            ConfigurationSection tierSec = tasksYml.getConfigurationSection(tierId);
            if (tierSec == null) {
                continue;
            }
            if (!tierById.containsKey(tierId)) {
                log.warning("tasks.yml: tier '" + tierId + "' is not in tiers.yml, skipped");
                continue;
            }
            List<TaskDef> pool = new ArrayList<>();
            for (String id : tierSec.getKeys(false)) {
                if (pool.size() >= MAX_TASKS_PER_TIER) {
                    log.warning("tasks.yml " + tierId + ": more than " + MAX_TASKS_PER_TIER + " tasks, the rest are ignored");
                    break;
                }
                ConfigurationSection s = tierSec.getConfigurationSection(id);
                String where = "tasks.yml " + tierId + "." + id;
                if (s == null || !validId(id)) {
                    log.warning(where + ": invalid task id");
                    continue;
                }
                TaskType type = TaskType.parse(s.getString("type"));
                if (type == null) {
                    log.warning(where + ": unknown type '" + s.getString("type") + "'");
                    continue;
                }
                Set<Material> mats = null;
                Set<EntityType> ents = null;
                List<String> targets = s.getStringList("targets");
                if (!targets.isEmpty()) {
                    if (type.target == TaskType.Target.MATERIAL) {
                        mats = parseMaterials(targets, type, where, log);
                        if (mats.isEmpty()) {
                            log.warning(where + ": no valid target, task skipped");
                            continue;
                        }
                    } else if (type.target == TaskType.Target.ENTITY) {
                        ents = parseEntities(targets, where, log);
                        if (ents.isEmpty()) {
                            log.warning(where + ": no valid target, task skipped");
                            continue;
                        }
                    }
                }
                if (type == TaskType.BREAK_BLOCK || type == TaskType.HARVEST) {
                    if (mats == null) {
                        trackAll = true;
                    } else {
                        placeTracked.addAll(mats);
                    }
                }
                int amount = Math.max(1, Math.min(1_000_000_000, s.getInt("amount", 1)));
                RewardSet reward = s.isConfigurationSection("reward")
                        ? RewardSet.parse(s.getConfigurationSection("reward"), where + " reward", log) : null;
                TaskDef def = new TaskDef(tierId + "/" + id, tierId, id, type, mats, ents, amount,
                        s.getString("display", id),
                        PluginConfig.material(s.getString("icon"), Material.PAPER),
                        Math.max(1, Math.min(10000, s.getInt("weight", 1))), reward);
                pool.add(def);
                all.put(def.key(), def);
            }
            byTier.put(tierId, List.copyOf(pool));
        }
        for (Tier t : tiers) {
            List<TaskDef> pool = byTier.getOrDefault(t.id(), List.of());
            if (pool.isEmpty()) {
                log.warning("tasks.yml: tier '" + t.id() + "' has no task");
            }
        }
        tasks = Map.copyOf(all);
        pools = Map.copyOf(byTier);
        trackedPlaceMaterials = trackAll ? null : Collections.unmodifiableSet(placeTracked);

        // ---- 連勝 ----
        int length = Math.max(1, Math.min(10000, streakYml.getInt("length", 50)));
        boolean loop = !"STAY".equalsIgnoreCase(streakYml.getString("after-end", "LOOP"));
        int interval = Math.max(0, Math.min(10000, streakYml.getInt("interval", 0)));
        Map<Integer, RewardSet> dayRewards = new LinkedHashMap<>();
        ConfigurationSection dr = streakYml.getConfigurationSection("day-rewards");
        if (dr != null) {
            for (String k : dr.getKeys(false)) {
                Integer d = parseInt(k);
                if (d == null || d < 1 || d > length) {
                    log.warning("streak.yml: day-rewards '" + k + "' is not between 1 and " + length);
                    continue;
                }
                dayRewards.put(d, RewardSet.parse(dr.getConfigurationSection(k), "streak.yml day " + k, log));
            }
        }
        streak = new StreakTrack(length, loop, interval,
                RewardSet.parse(streakYml.getConfigurationSection("interval-reward"), "streak.yml interval-reward", log),
                Map.copyOf(dayRewards));

        allComplete = RewardSet.parse(rewardsYml.getConfigurationSection("all-complete"), "rewards.yml all-complete", log);
        everyStamp = RewardSet.parse(rewardsYml.getConfigurationSection("every-stamp"), "rewards.yml every-stamp", log);
    }

    public Tier tier(String id) {
        return id == null ? null : tierById.get(id);
    }

    public Tier firstTier() {
        return tiers.getFirst();
    }

    public Tier next(Tier t) {
        int i = t.index() + 1;
        return i < tiers.size() ? tiers.get(i) : null;
    }

    public TaskDef task(String key) {
        return key == null ? null : tasks.get(key);
    }

    public List<TaskDef> pool(String tierId) {
        return pools.getOrDefault(tierId, List.of());
    }

    public int taskCount() {
        return tasks.size();
    }

    private static boolean validId(String id) {
        return id.matches("[A-Za-z0-9_-]{1,64}");
    }

    private static Integer parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Set<Material> parseMaterials(List<String> targets, TaskType type, String where, Logger log) {
        Set<Material> out = new HashSet<>();
        boolean blocks = type == TaskType.BREAK_BLOCK || type == TaskType.PLACE_BLOCK || type == TaskType.HARVEST;
        for (String raw : targets) {
            String s = raw.trim();
            if (s.startsWith("#")) {
                NamespacedKey key = NamespacedKey.fromString(s.substring(1).toLowerCase(Locale.ROOT));
                Tag<Material> tag = key == null ? null
                        : Bukkit.getTag(blocks ? Tag.REGISTRY_BLOCKS : Tag.REGISTRY_ITEMS, key, Material.class);
                if (tag == null && key != null) {
                    tag = Bukkit.getTag(blocks ? Tag.REGISTRY_ITEMS : Tag.REGISTRY_BLOCKS, key, Material.class);
                }
                if (tag == null) {
                    log.warning(where + ": unknown tag '" + s + "'");
                } else {
                    out.addAll(tag.getValues());
                }
                continue;
            }
            Material m = Material.matchMaterial(s);
            if (m == null) {
                log.warning(where + ": unknown material '" + s + "'");
            } else {
                out.add(m);
            }
        }
        return out;
    }

    private static Set<EntityType> parseEntities(List<String> targets, String where, Logger log) {
        Set<EntityType> out = new HashSet<>();
        for (String raw : targets) {
            String s = raw.trim().toLowerCase(Locale.ROOT);
            if (s.startsWith("#")) {
                NamespacedKey key = NamespacedKey.fromString(s.substring(1));
                Tag<EntityType> tag = key == null ? null : Bukkit.getTag(Tag.REGISTRY_ENTITY_TYPES, key, EntityType.class);
                if (tag == null) {
                    log.warning(where + ": unknown entity tag '" + raw + "'");
                } else {
                    out.addAll(tag.getValues());
                }
                continue;
            }
            NamespacedKey key = NamespacedKey.fromString(s);
            EntityType e = key == null ? null : Registry.ENTITY_TYPE.get(key);
            if (e == null || e == EntityType.UNKNOWN) {
                log.warning(where + ": unknown entity '" + raw + "'");
            } else {
                out.add(e);
            }
        }
        return out;
    }
}
