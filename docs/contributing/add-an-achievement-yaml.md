# Adding an Achievement (Custom Advancement)

Astera Custom Advancement は `content/advancements/<id>.yaml` 1 ファイルで
定義します (Wave 5p / ADR-0088)。Vanilla advancement は Wave 5server-harden
(`VanillaFeature.VanillaAdvancement`) で抑制されるので、player の
"trophy" は本 doc 経由で出します。Kotlin コードは触りません。

## 最小例 (first-kill)

```yaml
# content/advancements/first-kill.yaml
id: first-kill
title_key: astera.advancement.first_kill.title
description_key: astera.advancement.first_kill.description
icon_item: example-sword
frame: TASK
hidden: false

criteria:
  type: kill_count
  target: 1

rewards:
  - type: grant_currency
    currency: GOLD
    amount: 100
```

## Composite example (boss-defeat)

```yaml
# content/advancements/boss-defeat.yaml
id: boss-defeat
title_key: astera.advancement.boss_defeat.title
description_key: astera.advancement.boss_defeat.description
icon_item: example-sword
frame: CHALLENGE
hidden: false
parent_id: kills-100

criteria:
  type: all_of
  criteria:
    - type: kill_count
      target: 500
    - type: matches_won
      count: 50

rewards:
  - type: grant_currency
    currency: STAR
    amount: 50
  - type: grant_title
    title_id: boss-slayer
```

## Schema reference

`plugin/core/.../domain/advancement/CustomAdvancement.kt` が SSOT。

| Field | Type | Required | 説明 |
|---|---|---|---|
| `id` | `String` | ✅ | `AdvancementId`。`^[a-z][a-z0-9-]*$` |
| `title_key` | `String` | ✅ | i18n key |
| `description_key` | `String` | ✅ | i18n key |
| `icon_item` | `String` | ✅ | `ItemId` (`content/items/`) - GUI に出るアイコン |
| `frame` | `enum` | ✅ | `TASK` / `GOAL` / `CHALLENGE` (Bukkit Frame と一致) |
| `hidden` | `Boolean` | optional | default `false`。true = 達成までツリーに非表示 |
| `parent_id` | `String?` | optional | 親 advancement (tree 構造) |
| `criteria` | `CriteriaSpec` | ✅ | 達成条件 |
| `rewards` | `List<RewardSpec>` | optional | unlock 時の報酬 |

## Criteria 種別

Quest と共通の `AdvancementCriteriaDto`:

| `type` | 必須 |
|---|---|
| `kill_count` | `target: Int` |
| `matches_won` | `count: Int` |
| `item_acquired` | `item_id: String`, `count: Int?` |
| `craft_count` | `recipe_id: String`, `count: Int?` |
| `all_of` | `criteria: List<CriteriaSpec>` (全部達成) |
| `any_of` | `criteria: List<CriteriaSpec>` (どれか) |

## Reward 種別

Quest と同様: `grant_currency` / `grant_item` / `grant_title` /
`grant_advancement` (= 別 advancement を unlock)。

## Frame の意味

| Frame | 用途 |
|---|---|
| `TASK` | 通常 trophy。GUI で四角枠 |
| `GOAL` | 中間 milestone。GUI で丸枠 + 強調 |
| `CHALLENGE` | 高難度。GUI で星枠 + 大きく光る |

## Validation rule

- `id`: 重複 = `DuplicateId`、regex 違反 = `InvalidSchema`
- `parent_id` が `content/advancements/` に未定義 = `MissingReference`
- `icon_item` が `IItemRegistry` に未登録 = `MissingReference`
- `criteria.item_acquired.item_id` 未登録 = `MissingReference`
- `criteria.craft_count.recipe_id` 未登録 = `MissingReference`
- parent tree が循環していたら = parse error

## Reload command

```
/astera reload advancements
```

atomic swap。`IPlayerAdvancementProgressRepository` の per-player progress
は維持。新しい criteria は次の trigger event から評価される。

## Progress flow

- `PlayerKilled` / `MatchEnded` / `ItemAcquired` / `RecipeCrafted` の
  各 event → `ProgressAdvancementUseCase` が該当 advancement の criteria
  を increment
- 達成 → `GrantAdvancementRewardUseCase` → `AchievementUnlocked` event
  publish
- HUD / chat 通知は Wave H `NotifyOnAchievementUnlockedListener`
  (stats 側に住む — ADR-0022 で core が `stats.domain.event.*` を import
  できないため)

## Vanilla advancement との関係

`SuppressVanillaFeaturesOnServerBootListener` (Wave L) が起動時に
`VanillaFeature.VanillaAdvancement` を suppress するので、vanilla
advancement (`adventure/kill_a_mob` 等) は player に出ない。Astera は
本 doc の Custom Advancement だけが trophy。

## 参考

- ADR-0088 (Custom Advancement + NPC Dialog + Tutorial Sequencer)
- ADR-0125 (Content baseline expansion)
- ADR-0129 (Vanilla feature suppression default suppress)
- [[contributing/add-an-item-yaml]]
- [[contributing/add-a-quest-yaml]]
- [[contributing/add-a-recipe-yaml]]
