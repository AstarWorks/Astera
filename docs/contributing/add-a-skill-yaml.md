# Adding a Skill

Astera Skill は `content/skills/<id>.yaml` 1 ファイルで完結します
(Wave 5a / ADR-0030, Wave 5_skills-2 / ADR-0077)。Item とは別ファイルに
分かれており、Item の `skills:` 配列から id 参照で複数の Item に同じ
Skill を再利用できます。Kotlin コードは触りません。

## 最小例 (charged buff)

```yaml
# content/skills/sword-dash.yaml
id: sword-dash

name:
  ja: "加速チャージ"
  en: "Example Charge"
description:
  ja: "長押しで一定時間、移動速度が上昇する。"
  en: "Hold to gain a movement-speed buff for a short window."

archetype: buff
cooldown_ticks: 80
tags: [ buff, movement ]

params:
  type: buff
  status_effect_id: regen
  duration_ticks: 100

phases:
  archetype: buff
  cast:
    - type: play_sound
      targets: { kind: caster }
      sound_id: skills/charge-on
    - type: spawn_particle
      targets: { kind: caster }
      particle_id: example-burst
  expire:
    - type: play_sound
      targets: { kind: caster }
      sound_id: skills/charge-off
  removed: []
```

## Schema reference

| Field | Type | Required | 説明 |
|---|---|---|---|
| `id` | `String` | ✅ | Skill 識別子 (`SkillId`)。`^[a-z][a-z0-9-]*$` |
| `name` | `Map<locale, String>` | ✅ | inline i18n。最低 `ja` / `en` |
| `description` | `Map<locale, String>` | ✅ | inline i18n |
| `archetype` | `enum` | ✅ | `melee` / `projectile` / `aoe` / `buff` / `teleport` / `summon` |
| `cooldown_ticks` | `Long` | ✅ | デフォルト cooldown (≥ 0)。Item binding 側で上書き可 |
| `mana_cost` | `Int` | optional | デフォルト 0 |
| `charge_ticks` | `Long?` | optional | null = instant cast |
| `tags` | `List<String>` | optional | フィルタ用 (gui / 統計向け) |
| `params` | `Map<String, Any>` | ✅ | `type:` が `archetype` と整合する必要あり |
| `phases` | `PhaseSpec` | optional | `cast` / `hit` / `expire` / `removed` などの effect chain |

`params.type` ごとに必須フィールドが変わります (`Mechanic` の sealed
hierarchy)。詳細は `plugin/core/.../domain/skill/Mechanic.kt` の
KDoc を参照。

| `params.type` | 必須 |
|---|---|
| `projectile` | `launch_speed: Double` (blocks/tick), `max_lifetime_ticks: Long`, `hit_radius: Double?`. Damage is NOT a param: put a `damage` mechanic in `phases.hit_entity` (WP-G) |
| `melee` | `range: Double`, `damage: { base, attribute }` |
| `aoe` | `radius: Double`, `damage: { base, attribute }` |
| `buff` | `status_effect_id: String`, `duration_ticks: Long` |
| `teleport` | `max_distance: Double`, `requires_line_of_sight: Boolean` |
| `summon` | `mob_id: String`, `count: Int`, `lifetime_ticks: Long?` |

`damage.attribute` は `Element` enum: `physical` / `fire` / `electric` /
`wind` / `water` / `earth` (Wave 5a / ADR-0030)。

## Phase mechanic effects

`phases.<phase>` は `EffectStep` のリスト (Wave 5effects / ADR-0109)。

```yaml
phases:
  cast:
    - type: play_sound      # play_sound / spawn_particle / apply_status / fire_damage_tick
      targets:
        kind: caster        # caster / origin / hit_target / area_around_origin (radius+count)
      sound_id: skills/fire-cast
  hit:
    - type: spawn_particle
      targets:
        kind: hit_target
      particle_id: fire-impact
    - type: apply_status
      targets:
        kind: hit_target
      status_effect_id: burn
      duration_ticks: 100
```

## Validation rule

- `id`: 重複 = `ContentValidationError.DuplicateId`、`SkillId.regex` 違反
  = `InvalidSchema`
- `name.ja` / `name.en` 必須 (parity Konsist)
- `archetype` と `params.type` 不一致 = parse error (`SkillSpecParseError`)
- `cooldown_ticks` 負値 = parse error
- 参照 status_effect_id / particle_id / sound_id が未定義 =
  `ContentValidationError.MissingReference` (`BootValidateContentLifecycleListener`
  で起動時 walk)

## Reload command

```
/astera reload skills
```

atomic swap (parse error 1 件でも全 rollback)。

## Item からの利用

```yaml
# content/items/my-sword.yaml
skills:
  - trigger: left_click
    skill_id: sword-dash          # ← この Skill を参照
    cooldown_override_ticks: 40       # binding 側で上書き
```

同じ Skill を別 Item に異なる trigger で bind できます。

## 参考

- ADR-0030 (Combat depth foundation)
- ADR-0077 (Skill mechanic phase pipeline)
- ADR-0109 (Effect orchestration)
- [[contributing/add-an-item-yaml]]

## コンボ / スタック / ダッシュ系 mechanic

詳細な YAML 例は `content/skills/README.md` の「Combo / stack / dash mechanics」節。

- `loop.stride_ticks`: i 回目の反復は `i * stride` tick 後に実行 (0 = 一斉)。
- `apply_status.stack_count`: N スタック加算 (status の `max_stacks` で頭打ち)。`remove_status.stacks`: N スタック消費 (省略 = 全削除)。
- `has_status` 条件: `min_stacks` (既定 1)、`on: target|caster`。
- `damage.components[].per_stack`: `{ status_id, amount, on, consume }`。ダメージに `amount x スタック数` を加算 (乗数適用後)。スタックは被弾前に読み、`consume: true` は mechanic 全体の命中後に消費。
- `dash_strike`: 術者を `distance` / `ticks` で前進させ、カプセル (`radius`) に触れた対象へ `on_hit` を実行。`invulnerable` は `immunity` status を付与。
- `hit_stop`: 対象 (と `include_caster` で術者) を `ticks` 間その場に固定。
- `hit_stop` と knockback / knockup / propel の順序は自由: 固定中 (または同 tick 内の先行) の押し出しは解除時に適用される。
- `restore_resource { resource: ammo|energy, amount }`: 術者の保持アイテムの resource pool を回復 (上限でクランプ)。
- `damage.ignore_invulnerability: true`: 無敵フレームを無視し、多段ヒットの全 beat が当たる。
- `filter` targeter の condition は候補ごとに評価 (`target` = その候補)。`enemies` / `allies` に `radius` (既定 10)。
- 向き `away_from_caster` / `toward_caster` (`vertical` で Y 成分): 対象ごとの放射方向。
- `delay_ticks` > 0 / `period_ticks` / loop stride の mechanic は発動時の術者の現在位置・視線で再解決される (ダッシュ追従)。cast 時の姿勢を使うなら `snapshot_pose: true`。`condition` も発動時に評価。
- `archetype: sequence` (`params: { type: sequence }`, `phases: { type: sequence, cast: [...] }`): cast フェーズだけの mechanics コンテナ。タイムライン専用スキル向け。
- melee / aoe の `params.damage` も `per_stack` に対応。`resist.<属性>` は無装備でも効く。
- VFX: `end_tick` は全スロット共通の絶対 tick。スロットごとの寿命は `slot_lifetime_ticks`。`group_keyframes` で formation 全体を平行移動 / 回転 / 拡縮。
- スタック専用 status は `content/status/` の `kind: stack_debuff|stack_buff` + `params: { type: marker }`。
