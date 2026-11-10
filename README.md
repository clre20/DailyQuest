# DailyQuest

DailyQuest 是 Paper 26.x 的每日任務插件。玩家每天從自己所在階級的任務池抽到 10 個任務，一天完成 5 個就能在「蓋章地圖」蓋一個章，同時累積連勝。地圖蓋滿就升到下一階，從新手一路到造物者；連勝中斷可以用補簽卷接回。所有操作都在箱子選單用左鍵完成，數值全部可以在設定檔修改。

## 功能特性

- **每日任務**
  - 每天依權重抽出 10 個不重複的任務（數量可設定），台灣時間 00:00 重置（時區與時間可設定）
  - 每天 5 次重抽，一次換一個任務，換成同一階的其他任務；當天出現過或完成過的任務不會再抽到
  - 34 種任務類型：挖掘、放置、收成、擊殺、合成、熔煉、釣魚、繁殖、馴服、剪毛、擠奶、交易、附魔、鐵砧、鍛造、釀造、吃喝、拾取、射箭、投擲、經驗、升級、跳躍、睡覺、突襲、行走、衝刺、游泳、攀爬、搭船、礦車、騎乘、鞘翅、在線時間
  - 目標可以寫材質、生物或標籤（例如 `#minecraft:logs`）
- **兩條獎勵路線**
  - 進度路線（蓋章地圖）：10 個階級，新手 20 格、每階多 5 格，每一格都能設獎勵，最後一格就是這一階的「底」；蓋滿自動升階，最後一階蓋滿後停住，加入新階級會自動接續
  - 連勝路線：預設 50 格，可設每隔幾天的固定獎勵與指定天數獎勵，走到底後可選擇重新開始或停住
- **補簽卷**
  - 只是數字，沒有實體物品，不會被複製或交易
  - 連勝中斷時登入提示，玩家自己到連勝路線左鍵補簽；可補天數、補簽期限都可設定
- **獎勵**
  - 錢（Vault，例如 TheosisEconomy）、第二貨幣（ExcellentEconomy，預設企鵝幣 `penguin`）、補簽卷、物品（支援 NBT／元件字串）、後台指令，可以混搭
  - 背包滿時物品進入待領取清單，不會掉在地上
  - 當天全部完成、每次蓋章、地圖格子、升階、連勝里程碑都可以設獎勵
- **防刷**
  - 自己放置的方塊、石頭生成器產生的方塊再挖掉不算
  - 生怪磚、生怪蛋生出的生物不算；自己丟出的物品撿起不算；創造模式不算
  - 可指定不計算進度的世界
- **效能**
  - 事件第一行先檢查「有沒有線上玩家有這種任務」，沒有就直接返回
  - 距離與在線時間用統計值定時取樣，不監聽玩家移動
  - 記憶體快取＋髒資料標記，定時批次寫入，單一 I/O 執行緒；YAML 或 SQLite 可選
- **其他**
  - PlaceholderAPI 變數、ActionBar 進度、音效
  - 開服與 `/dq version` 檢查 GitHub Releases 更新，結果快取 10 分鐘

## 指令與權限

| 指令 | 說明 | 執行者 | 權限 | 預設 |
|---|---|---|---|---|
| `/dq` | 開啟每日任務選單 | 玩家 | `dailyquest.use` | 所有人 |
| `/dq map` | 開啟蓋章地圖 | 玩家 | `dailyquest.use` | 所有人 |
| `/dq streak` | 開啟連勝路線（補簽在這裡） | 玩家 | `dailyquest.use` | 所有人 |
| `/dq help` | 指令說明 | 玩家／後台 | `dailyquest.help` | 所有人 |
| `/dq version` | 檢查更新；有新版時登入 3 秒後提醒 | 玩家／後台 | `dailyquest.version` | OP |
| `/dq reload` | 重新讀取所有設定檔 | 玩家／後台 | `dailyquest.admin.reload` | OP |
| `/dq admin info <玩家>` | 查看玩家資料 | 玩家／後台 | `dailyquest.admin.manage` | OP |
| `/dq admin reset <玩家>` | 重新抽今天的任務 | 玩家／後台 | `dailyquest.admin.manage` | OP |
| `/dq admin complete <玩家> <第幾個>` | 直接完成任務 | 玩家／後台 | `dailyquest.admin.manage` | OP |
| `/dq admin settier <玩家> <階級ID>` | 設定階級 | 玩家／後台 | `dailyquest.admin.manage` | OP |
| `/dq admin setstamps <玩家> <數量>` | 設定本階章數 | 玩家／後台 | `dailyquest.admin.manage` | OP |
| `/dq admin setstreak <玩家> <天數>` | 設定連勝 | 玩家／後台 | `dailyquest.admin.manage` | OP |
| `/dq admin ticket <give\|take\|set> <玩家> <數量>` | 補簽卷 | 玩家／後台 | `dailyquest.admin.manage` | OP |
| `/dq admin itemstring` | 取得手上物品的獎勵字串 | 玩家 | `dailyquest.admin.manage` | OP |

> `/dq` 是 `/dailyquest` 的別名。`dailyquest.admin` 包含所有權限。離線玩家也能用 `info`、`settier`、`setstamps`、`setstreak`、`ticket`（必須曾經進過伺服器）。

## 使用範例

### 1. 把有附魔的物品設成獎勵

```bash
# 手拿物品後輸入，點擊聊天室的字串即可複製
/dq admin itemstring
```

把複製的字串貼到 `tiers.yml`、`streak.yml` 或 `rewards.yml` 的 `items`：

```yaml
items:
  - item: 'minecraft:diamond_sword[enchantments={levels:{"minecraft:sharpness":5}}]'
    amount: 1
```

### 2. 新增一個任務

在 `tasks.yml` 的階級底下加入：

```yaml
novice:
  copper:
    type: BREAK_BLOCK
    targets: ['#minecraft:copper_ores']
    amount: 16
    display: '&f挖掘 %amount% 個銅礦'
    icon: COPPER_ORE
    reward:
      money: 30
```

```bash
/dq reload
```

### 3. 從商店發補簽卷

```bash
/dq admin ticket give Steve 1
```

## 設定檔

| 檔案 | 內容 |
|---|---|
| `config.yml` | 每日任務數、重抽次數、蓋章門檻、時區、補簽規則、防刷、經濟、儲存方式、選單外觀、更新檢查（自動補齊） |
| `messages.yml` | 所有文字（繁體中文，自動補齊） |
| `tiers.yml` | 階級、格數、各格獎勵、該階任務預設獎勵 |
| `streak.yml` | 連勝路線長度、走到底後的行為、里程碑獎勵 |
| `rewards.yml` | 當天全部完成、每次蓋章的獎勵 |
| `tasks.yml` | 各階級的任務池 |

## PlaceholderAPI

| 變數 | 說明 |
|---|---|
| `%dailyquest_tier%` | 階級名稱 |
| `%dailyquest_tier_id%`／`%dailyquest_tier_index%` | 階級 ID／第幾階 |
| `%dailyquest_stamps%`／`%dailyquest_stamps_required%` | 本階章數／需要章數 |
| `%dailyquest_streak%`／`%dailyquest_best_streak%` | 連勝／最高連勝 |
| `%dailyquest_completed%`／`%dailyquest_total%` | 今日完成數／任務數 |
| `%dailyquest_rerolls_left%` | 剩餘重抽 |
| `%dailyquest_tickets%` | 補簽卷 |
| `%dailyquest_stamped_today%` | 今天是否已蓋章 |
| `%dailyquest_streak_broken%`／`%dailyquest_patch_cost%` | 連勝是否中斷可補／補簽需要張數 |

## 需求

- Paper 26.1 以上、Java 25
- 選用：Vault＋經濟插件、ExcellentEconomy、PlaceholderAPI
