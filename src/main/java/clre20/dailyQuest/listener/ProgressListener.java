package clre20.dailyQuest.listener;

import clre20.dailyQuest.config.PluginConfig;
import clre20.dailyQuest.quest.QuestService;
import clre20.dailyQuest.quest.TaskType;
import com.destroystokyo.paper.event.player.PlayerJumpEvent;
import io.papermc.paper.event.player.PlayerDeepSleepEvent;
import io.papermc.paper.event.player.PlayerTradeEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BrewingStand;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.FurnaceExtractEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.SmithItemEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerLevelChangeEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.event.raid.RaidFinishEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.BrewerInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.view.AnvilView;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 任務進度事件。每個處理器第一行先檢查「有沒有線上玩家有這種任務」（陣列查詢），沒有就直接返回。
 * 全部在 MONITOR 優先度、忽略已取消的事件，不修改事件。
 */
public final class ProgressListener implements Listener {

    /** 要看成熟度的作物；其他收成目標（甘蔗、西瓜等）改用「不是玩家放置的」判斷。 */
    private static final Set<Material> AGE_CROPS = EnumSet.of(Material.WHEAT, Material.CARROTS, Material.POTATOES,
            Material.BEETROOTS, Material.NETHER_WART, Material.COCOA, Material.TORCHFLOWER_CROP, Material.PITCHER_CROP);

    private static final int BREW_MEMORY = 2048;

    private final QuestService quest;
    private final PlacedBlocks placed;
    private PluginConfig cfg;
    /** 釀造台位置 → 最後打開的玩家（BrewEvent 沒有玩家）。 */
    private final LinkedHashMap<BrewKey, UUID> brewers = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<BrewKey, UUID> eldest) {
            return size() > BREW_MEMORY;
        }
    };

    private record BrewKey(UUID world, int x, int y, int z) {
        static BrewKey of(Location l) {
            return new BrewKey(l.getWorld().getUID(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
        }
    }

    public ProgressListener(QuestService quest, PlacedBlocks placed, PluginConfig cfg) {
        this.quest = quest;
        this.placed = placed;
        this.cfg = cfg;
    }

    public void reload(PluginConfig cfg) {
        this.cfg = cfg;
        placed.setLimit(cfg.placedCacheSize);
    }

    public void clear() {
        brewers.clear();
        placed.clear();
    }

    // ------------------------------------------------------------ 方塊

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Block b = e.getBlockPlaced();
        Material m = b.getType();
        if (cfg.ignorePlacedBlocks && shouldTrack(m)) {
            placed.add(b);
        }
        if (quest.hasActive(TaskType.PLACE_BLOCK)) {
            quest.progress(e.getPlayer(), TaskType.PLACE_BLOCK, m, 1);
        }
    }

    /** 石頭生成器等自然生成的方塊也當作「放置的」，避免刷挖掘任務。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onForm(BlockFormEvent e) {
        if (cfg.ignorePlacedBlocks && shouldTrack(e.getNewState().getType())) {
            placed.add(e.getBlock());
        }
    }

    private boolean shouldTrack(Material m) {
        Set<Material> tracked = quest.defs().trackedPlaceMaterials;
        return tracked == null || tracked.contains(m);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        boolean breakTask = quest.hasActive(TaskType.BREAK_BLOCK);
        boolean harvestTask = quest.hasActive(TaskType.HARVEST);
        Block b = e.getBlock();
        boolean wasPlaced = cfg.ignorePlacedBlocks && placed.remove(b);
        if (!breakTask && !harvestTask) {
            return;
        }
        Material m = b.getType();
        Player p = e.getPlayer();
        if (breakTask && !wasPlaced) {
            quest.progress(p, TaskType.BREAK_BLOCK, m, 1);
        }
        if (harvestTask) {
            if (AGE_CROPS.contains(m)) {
                if (b.getBlockData() instanceof Ageable a && a.getAge() >= a.getMaximumAge()) {
                    quest.progress(p, TaskType.HARVEST, m, 1);
                }
            } else if (!wasPlaced) {
                quest.progress(p, TaskType.HARVEST, m, 1);
            }
        }
    }

    @EventHandler
    public void onWorldUnload(WorldUnloadEvent e) {
        placed.unloadWorld(e.getWorld().getUID());
    }

    // ------------------------------------------------------------ 生物

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent e) {
        if (!quest.hasActive(TaskType.KILL_ENTITY)) return;
        LivingEntity dead = e.getEntity();
        Player killer = dead.getKiller();
        if (killer == null) return;
        if (cfg.ignoreSpawnerMobs && dead.fromMobSpawner()) return;
        if (cfg.ignoreSpawnEggMobs) {
            CreatureSpawnEvent.SpawnReason r = dead.getEntitySpawnReason();
            if (r == CreatureSpawnEvent.SpawnReason.SPAWNER_EGG || r == CreatureSpawnEvent.SpawnReason.DISPENSE_EGG) {
                return;
            }
        }
        quest.progress(killer, TaskType.KILL_ENTITY, dead.getType(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreed(EntityBreedEvent e) {
        if (quest.hasActive(TaskType.BREED) && e.getBreeder() instanceof Player p) {
            quest.progress(p, TaskType.BREED, e.getEntity().getType(), 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTame(EntityTameEvent e) {
        if (quest.hasActive(TaskType.TAME) && e.getOwner() instanceof Player p) {
            quest.progress(p, TaskType.TAME, e.getEntity().getType(), 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShear(PlayerShearEntityEvent e) {
        if (quest.hasActive(TaskType.SHEAR)) {
            quest.progress(e.getPlayer(), TaskType.SHEAR, e.getEntity().getType(), 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMilk(PlayerInteractEntityEvent e) {
        if (!quest.hasActive(TaskType.MILK)) return;
        Entity ent = e.getRightClicked();
        EntityType t = ent.getType();
        if (t != EntityType.COW && t != EntityType.MOOSHROOM && t != EntityType.GOAT) return;
        if (ent instanceof org.bukkit.entity.Ageable a && !a.isAdult()) return;
        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItem(e.getHand());
        if (hand.getType() != Material.BUCKET) return;
        quest.progress(p, TaskType.MILK, t, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRaid(RaidFinishEvent e) {
        if (!quest.hasActive(TaskType.RAID_WIN)) return;
        for (Player p : e.getWinners()) {
            quest.progress(p, TaskType.RAID_WIN, 1);
        }
    }

    // ------------------------------------------------------------ 物品

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent e) {
        if (!quest.hasActive(TaskType.CRAFT)) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getAction() == InventoryAction.NOTHING || noRoom(e, p)) return;
        ItemStack result = e.getInventory().getResult();
        if (result == null || result.getType().isAir()) return;
        int times = 1;
        if (e.isShiftClick()) {
            int min = Integer.MAX_VALUE;
            for (ItemStack in : e.getInventory().getMatrix()) {
                if (in != null && !in.getType().isAir()) {
                    min = Math.min(min, in.getAmount());
                }
            }
            times = min == Integer.MAX_VALUE ? 1 : min;
        }
        quest.progress(p, TaskType.CRAFT, result.getType(), result.getAmount() * times);
    }

    /** Shift 點擊成品但背包沒有空格時，實際上拿不到東西，不算。 */
    private static boolean noRoom(InventoryClickEvent e, Player p) {
        return e.isShiftClick() && p.getInventory().firstEmpty() < 0;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSmelt(FurnaceExtractEvent e) {
        if (quest.hasActive(TaskType.SMELT)) {
            quest.progress(e.getPlayer(), TaskType.SMELT, e.getItemType(), e.getItemAmount());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        if (!quest.hasActive(TaskType.FISH)) return;
        if (e.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;
        if (e.getCaught() instanceof Item item) {
            quest.progress(e.getPlayer(), TaskType.FISH, item.getItemStack().getType(), 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrade(PlayerTradeEvent e) {
        if (quest.hasActive(TaskType.TRADE)) {
            quest.progress(e.getPlayer(), TaskType.TRADE, e.getTrade().getResult().getType(), 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent e) {
        if (quest.hasActive(TaskType.ENCHANT)) {
            quest.progress(e.getEnchanter(), TaskType.ENCHANT, e.getItem().getType(), 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAnvil(InventoryClickEvent e) {
        if (!quest.hasActive(TaskType.ANVIL)) return;
        if (e.getRawSlot() != 2 || !(e.getInventory() instanceof AnvilInventory)) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;
        ItemStack result = e.getCurrentItem();
        if (result == null || result.getType().isAir()) return;
        if (e.getAction() == InventoryAction.NOTHING || noRoom(e, p)) return;
        if (e.getView() instanceof AnvilView view) {
            int cost = view.getRepairCost();
            if (cost <= 0 || p.getLevel() < cost) return;
        }
        quest.progress(p, TaskType.ANVIL, result.getType(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSmith(SmithItemEvent e) {
        if (!quest.hasActive(TaskType.SMITHING)) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;
        ItemStack result = e.getInventory().getResult();
        if (result == null || result.getType().isAir() || e.getAction() == InventoryAction.NOTHING || noRoom(e, p)) return;
        quest.progress(p, TaskType.SMITHING, result.getType(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpenBrewer(InventoryOpenEvent e) {
        if (!quest.hasActive(TaskType.BREW)) return;
        if (e.getInventory() instanceof BrewerInventory inv && inv.getHolder() instanceof BrewingStand stand
                && e.getPlayer() instanceof Player p) {
            brewers.put(BrewKey.of(stand.getLocation()), p.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBrew(BrewEvent e) {
        if (!quest.hasActive(TaskType.BREW)) return;
        UUID who = brewers.get(BrewKey.of(e.getBlock().getLocation()));
        if (who == null) return;
        Player p = Bukkit.getPlayer(who);
        if (p == null) return;
        for (ItemStack it : e.getResults()) {
            if (it != null && !it.getType().isAir()) {
                quest.progress(p, TaskType.BREW, it.getType(), 1);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        if (quest.hasActive(TaskType.CONSUME)) {
            quest.progress(e.getPlayer(), TaskType.CONSUME, e.getItem().getType(), 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (!quest.hasActive(TaskType.PICKUP)) return;
        if (!(e.getEntity() instanceof Player p)) return;
        Item item = e.getItem();
        if (cfg.ignoreThrownItems && item.getThrower() != null) return;
        ItemStack stack = item.getItemStack();
        int amount = stack.getAmount() - e.getRemaining();
        quest.progress(p, TaskType.PICKUP, stack.getType(), amount);
    }

    // ------------------------------------------------------------ 動作

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent e) {
        if (quest.hasActive(TaskType.SHOOT_BOW) && e.getEntity() instanceof Player p) {
            quest.progress(p, TaskType.SHOOT_BOW, 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onThrow(ProjectileLaunchEvent e) {
        if (quest.hasActive(TaskType.THROW) && e.getEntity().getShooter() instanceof Player p) {
            quest.progress(p, TaskType.THROW, e.getEntity().getType(), 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onExp(PlayerExpChangeEvent e) {
        if (quest.hasActive(TaskType.GAIN_EXP) && e.getAmount() > 0) {
            quest.progress(e.getPlayer(), TaskType.GAIN_EXP, e.getAmount());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLevel(PlayerLevelChangeEvent e) {
        if (quest.hasActive(TaskType.LEVEL_UP) && e.getNewLevel() > e.getOldLevel()) {
            quest.progress(e.getPlayer(), TaskType.LEVEL_UP, e.getNewLevel() - e.getOldLevel());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJump(PlayerJumpEvent e) {
        if (quest.hasActive(TaskType.JUMP)) {
            quest.progress(e.getPlayer(), TaskType.JUMP, 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSleep(PlayerDeepSleepEvent e) {
        if (quest.hasActive(TaskType.SLEEP)) {
            quest.progress(e.getPlayer(), TaskType.SLEEP, 1);
        }
    }
}
