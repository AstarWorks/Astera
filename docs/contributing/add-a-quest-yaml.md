# Adding a Quest Chain

Astera Quest は `content/quests/<id>.yaml` 1 ファイルで定義します
(Wave 5s / ADR-0091)。複数 stage を順番にこなしていく chain 型。
Kotlin コードは触りません。

## 最小例 (2-stage)

```yaml
# content/quests/forest-exploration.yaml
id: forest-exploration
title_key: astera.quest.forest-exploration.title

stages:
  - id: hunt-forest-mobs
    title_key: astera.quest.forest-exploration.hunt-forest-mobs.title
    description_key: astera.quest.forest-exploration.hunt-forest-mobs.description
    criteria:
      type: kill_count
      target: 10
    rewards:
      - type: grant_currency
        currency: GOLD
        amount: 100
    following_stage_id: prove-prowess

  - id: prove-prowess
    title_key: astera.quest.forest-exploration.prove-prowess.title
    description_key: astera.quest.forest-exploration.prove-prowess.description
    criteria:
      type: matches_won
      count: 1
    rewards:
      - type: grant_title
        title_id: forest-ranger
```

## Schema reference

`plugin/core/.../domain/quest/QuestChain.kt` + `QuestStage.kt` が SSOT。

| Field | Type | Required | 説明 |
|---|---|---|---|
| `id` | `String` | ✅ | `QuestChainId`。`^[a-z][a-z0-9-]*$` |
| `title_key` | `String` | ✅ | chain 全体のタイトル i18n key |
| `stages` | `List<QuestStage>` | ✅ | ≥ 1 |
| `stages[].id` | `String` | ✅ | chain 内で unique |
| `stages[].title_key` | `String` | ✅ | stage のタイトル i18n key |
| `stages[].description_key` | `String` | ✅ | stage の説明 i18n key |
| `stages[].criteria` | `CriteriaSpec` | ✅ | 達成条件 (sealed) |
| `stages[].rewards` | `List<RewardSpec>` | optional | 達成時の報酬 |
| `stages[].following_stage_id` | `String?` | optional | null = chain 終了 |

## Criteria 種別

| `type` | 必須 |
|---|---|
| `kill_count` | `target: Int` |
| `matches_won` | `count: Int` |
| `item_acquired` | `item_id: String`, `count: Int?` (default 1) |
| `craft_count` | `recipe_id: String`, `count: Int?` |
| `composite_all_of` | `criteria: List<CriteriaSpec>` (全部達成) |
| `composite_any_of` | `criteria: List<CriteriaSpec>` (どれか達成) |

## Reward 種別

| `type` | 必須 |
|---|---|
| `grant_currency` | `currency: enum`, `amount: Long` |
| `grant_item` | `item_id: String`, `count: Int?` |
| `grant_title` | `title_id: String` |
| `grant_advancement` | `advancement_id: String` |

## Validation rule

- `id`: 重複 = `DuplicateId`、regex 違反 = `InvalidSchema`
- `stages[].id` 重複 (同一 chain 内) = parse error
- `following_stage_id` が `stages[].id` のどれにも一致しない = `MissingReference`
- `criteria.item_acquired.item_id` が `IItemRegistry` に未登録 = `MissingReference`
- `criteria.craft_count.recipe_id` が `ICraftingRecipeRegistry` に未登録 =
  `MissingReference`
- `rewards.grant_title.title_id` が `INameplateTitleRegistry` に未登録 =
  warning (title 未実装でも chain は進む)

## Reload command

```
/astera reload quests
```

atomic swap。進行中の player progress は維持 (`IPlayerQuestProgressRepository`)、
新しい stage 構造は次の `QuestStageCompleted` 評価から有効。

## Progress と event

- `StartQuestChainUseCase` → `QuestChainStarted` publish
- 各 trigger event (`PlayerKilled` / `MatchEnded` / `ItemAcquired` /
  `RecipeCrafted`) → `ProgressQuestUseCase` が該当 stage の criteria を
  evaluate
- 完了 → `GrantQuestRewardUseCase` → `QuestStageCompleted` publish
- `following_stage_id == null` → `QuestChainCompleted` publish
- HUD / chat 通知は Wave H 5content-depth の
  `UpdateQuestProgressOnQuestStageCompletedListener` (Core 側) で配信

## 参考

- ADR-0091 (Quest chain + crafting recipe foundation)
- ADR-0125 (Content baseline expansion)
- [[contributing/add-an-item-yaml]]
- [[contributing/add-a-recipe-yaml]]
- [[contributing/add-an-achievement-yaml]]
