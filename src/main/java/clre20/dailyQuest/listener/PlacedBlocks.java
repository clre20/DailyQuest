package clre20.dailyQuest.listener;

import org.bukkit.block.Block;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 記住「玩家放置的方塊」，挖掉時不算任務。
 * 只存世界 UUID 與座標壓縮成的 long，不持有 World／Block；每個世界有數量上限，超過時忘記最舊的。
 */
public final class PlacedBlocks {

    private final Map<UUID, LinkedHashMap<Long, Boolean>> worlds = new HashMap<>();
    private int limit;

    public PlacedBlocks(int limit) {
        this.limit = limit;
    }

    public void setLimit(int limit) {
        this.limit = limit;
    }

    private static long key(Block b) {
        // x、z 各 26 位元，y 12 位元（-2048～2047）
        return ((long) (b.getX() & 0x3FFFFFF) << 38) | ((long) (b.getZ() & 0x3FFFFFF) << 12) | (b.getY() & 0xFFF);
    }

    public void add(Block b) {
        LinkedHashMap<Long, Boolean> map = worlds.computeIfAbsent(b.getWorld().getUID(), k -> new LinkedHashMap<>(1024, 0.75f) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
                return size() > limit;
            }
        });
        map.put(key(b), Boolean.TRUE);
    }

    /** 方塊被挖掉：回傳它是否為玩家放置的，並移除紀錄。 */
    public boolean remove(Block b) {
        LinkedHashMap<Long, Boolean> map = worlds.get(b.getWorld().getUID());
        return map != null && map.remove(key(b)) != null;
    }

    public void unloadWorld(UUID world) {
        worlds.remove(world);
    }

    public void clear() {
        worlds.clear();
    }
}
