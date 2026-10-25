# Adding a Mob

Astera Mob は `content/mobs/<id>.yaml` 1 ファイルで完結します
(Wave 5pve / ADR-0085, Wave 5pve-survival / ADR-0107)。Skill との関連は
`skill_bindings:` で id 参照。Kotlin コードは触りません。

## 最小例 (forest-wolf)

```yaml
# content/mobs/forest-wolf.yaml
id: astera:forest-wolf

display_name:
  en: "<gray>Forest Wolf</gray>"
  ja: "<gray>森狼</gray>"

base_entity_type: MOB

attributes:
  mob.max_hp: 25.0
  mob.damage: 4.0
  mob.speed: 1.2

appearance:
  custom_name_visible: true
  name_tag_color:
    r: 200
    g: 200
    b: 200
  glowing: false

equipment: []

skill_bindings:
  - type: on_player_near
    skill_id: wolf-bite
    range: 5.0
    cooldown_ticks: 40
  - type: on_hp_threshold
    skill_id: wolf-howl
    percent: 0.3

loot_table: astera:forest-wolf-drops

spawn_conditions:
  biome_keys:
    - "minecraft:forest"
    - "minecraft:dark_forest"
  time_of_day_range:
    - 0
    - 14000

ai: pathfind
```

## Schema reference

`plugin/core/.../domain/mob/MobSpec.kt` が SSOT。

| Field | Type | Required | 説明 |
|---|---|---|---|
| `id` | `String` | ✅ | `MobId`。`namespace:name` 形式が慣習 (`astera:forest-wolf`) |
| `display_name.{en,ja}` | `String` | ✅ | inline i18n、name tag に出る |
| `base_entity_type` | `enum` | ✅ | `MOB` / `BOSS` / `PASSIVE`（base AI 振る舞い） |
| `attributes.mob.max_hp` | `Double` | ✅ | 最大 HP |
| `attributes.mob.damage` | `Double` | ✅ | 基本攻撃 damage |
| `attributes.mob.speed` | `Double` | optional | デフォルト 1.0 |
| `appearance.*` | `Map` | optional | name tag 色 / 可視性 / glowing |
| `equipment` | `List<ItemRef>` | optional | 装備品 (`item_id` で `IItemRegistry` 参照) |
| `skill_bindings[]` | `List<MobSkillBinding>` | optional | 発動条件付き skill 参照 |
| `loot_table` | `String?` | optional | `content/pve/loot-tables/<id>.yaml` 参照 |
| `spawn_conditions.*` | `Map` | optional | biome / 時刻 / 周辺光量など |
| `ai` | `enum` | optional | `pathfind` / `flying` / `passive` / `idle` |

## skill_bindings の trigger 種別

| `type` | 必須 |
|---|---|
| `on_player_near` | `range: Double` |
| `on_hp_threshold` | `percent: Double` (0.0–1.0) |
| `on_attack` | (なし; 基本攻撃 swing) |
| `on_damaged` | (なし; 被弾) |
| `on_spawn` | (なし; 1 回だけ) |
| `on_periodic` | `period_ticks: Long` |

`skill_id` は `content/skills/<id>.yaml` の id を参照。`cooldown_ticks` は
binding 単位の上書き。

## Validation rule

- `id`: `namespace:name` 形式必須 (`astera:forest-wolf` OK、`forest-wolf` NG)
- `attributes.mob.max_hp` ≤ 0 = parse error
- 参照 `skill_id` が `content/skills/` に存在しない = `MissingReference`
- 参照 `loot_table` が `content/pve/loot-tables/` に存在しない = `MissingReference`
- `spawn_conditions.time_of_day_range` は `[0..23999]` 必須 (Minecraft day = 24000 ticks)

## Reload command

```
/astera reload mobs
/astera reload loot-tables   # ペアで運用すると good
```

atomic swap。spawn 済み mob は livel 上で更新されない (再 spawn で適用)。

## Spawn pipeline

`Wave 5c Star Generator` が `Stage` の `spawn_points` ＋ `MobSpec.spawn_conditions`
から確率的に spawn 候補を作る。`IMobAdapter` (`MinimalMobAdapter`) が `MobSpec` を Bukkit Entity に翻訳。

```
content/mobs/forest-wolf.yaml         # Spec
content/pve/loot-tables/forest-wolf-drops.yaml   # Loot (別 doc)
```

## Boss

`base_entity_type: BOSS` の場合は `BossSpec` 経由で扱われ、`IBossLifecycleService`
が boss bar / 復活 / loot 配分などを別レーンで管理 (Wave 5pve-boss)。

## 参考

- ADR-0085 (PvE mob lifecycle foundation)
- ADR-0107 (Survival party-shared instance)
- [[contributing/add-an-item-yaml]]
- [[contributing/add-a-skill-yaml]]
