package clre20.dailyQuest.quest;

import clre20.dailyQuest.reward.RewardSet;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;

import java.util.Set;

/**
 * 任務池裡的一個任務。
 *
 * @param key       「階級ID/任務ID」，存進玩家資料
 * @param materials 目標材質；null＝任何
 * @param entities  目標生物；null＝任何
 * @param reward    自訂獎勵；null＝用階級預設
 */
public record TaskDef(String key, String tierId, String id, TaskType type,
                      Set<Material> materials, Set<EntityType> entities,
                      int amount, String display, Material icon, int weight, RewardSet reward) {

    public boolean matches(Material m) {
        return materials == null || (m != null && materials.contains(m));
    }

    public boolean matches(EntityType e) {
        return entities == null || (e != null && entities.contains(e));
    }
}
