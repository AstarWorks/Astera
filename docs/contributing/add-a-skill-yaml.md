# Adding a Skill

Limbus参照武器の制作・修正は [統一手順書](limbus-skill-reproduction.md) から始める。このページはYAML記述のリファレンス。

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
| `ego_cost` | `Int?` | optional | E.G.O ゲージ消費量 (0..100、ADR-0231)。不足なら cast 拒否 (`astera.combat.cast.insufficient.ego`)。`trigger: drop` (Q) に bind する究極技用。エネルギーも消費させたくなければ `resource_cost: 0` を併記 |
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

## Cover damage (`block_damage` / `block_place`, ADR-0280)

A skill never touches cover blocks unless it opts in.

```yaml
block_damage:          # optional
  amount: 20           # flat HP chip; with `tool`, the matrix base (100 = one swing of that tool)
  radius: 0            # blocks around the impact / cast point; 0 = the one cover block struck (max 6)
  tool: PICKAXE/IRON   # optional; count as this tool (right tool 100, wrong 25 or 0)
  reach: 4             # cast-time skills (melee, sequence, ...): look-ray length (max 16)
  shape: ball          # ball | line (line cuts a tube of `reach` blocks)
block_place:           # optional; a temporary cover wall in front of the caster, removed at match end
  block: "astera:sandbags"
  distance: 2          # 1..6
  width: 3             # 1..7
  height: 2            # 1..4
```

Projectile skills strike where the projectile lands, AOE around the cast point, the rest along the look ray. Guns and spells use small flat chips (cover has 200 to 500 HP); large breaking is for the field tool skills. Cover is not regenerated during a match and is rebuilt when it ends.

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

### E.G.O 枠 (究極技)

`drop` (Q) は E.G.O 枠。ゲージ (`content/balance/ego.yaml`: 与ダメ +5 / 被ダメ +4 / 戦闘外で減衰 /
死亡・マッチ開始でリセット) が満タンで光り (`E.G.O READY`)、`ego_cost: 100` の Skill が撃てる。

```yaml
# content/skills/my-ego.yaml
ego_cost: 100
resource_cost: 0        # エネルギーは取らない (任意)
# content/items/my-weapon.yaml
skills:
  - trigger: drop           # Q = E.G.O
    skill_id: my-ego
  - trigger: sneak_drop     # Shift+Q = リロード (銃)
    skill_id: pistol-reload
```

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
- VFX display particles: `kind: { type: quad, color: "#RRGGBB", alpha: 0..1, glyph?, see_through? }`。keyframe に `color` / `alpha` で色・透明度を補間。`emit: { count (≤40), rate_per_tick?, speed: [min,max], spread_deg, direction: forward|up|outward|tangent|custom[x,y,z], gravity, drag, life_ticks: [min,max], size: [min,max], size_over_life / color_over_life / alpha_over_life / spin_over_life: [{ t: 0..1, v, ease? }, ...] (キー 1 個以上のカーブ)、sprite 要素なら start_frame / frame_ticks }` で粒子を放出 (デモ: `content/vfx/demo-quad-sparks.yaml`、較正は `docs/contributing/vfx-art-direction.md` §5)。
- スタック専用 status は `content/status/` の `kind: stack_debuff|stack_buff` + `params: { type: marker }`。

## v2 条件付きメカニクス (`astera/v2`, SKL-10)

v2 skill (`content/skills/<id>/skill.yaml`) の条件は全部 CEL (`"= ..."`) で書く。専用 op ではなく汎用文法。

| 目的 | 書き方 |
|---|---|
| op / timeline row を条件で実行 | `when: "= nearest_enemy_dist(caster) < 3.6"` (全 op、全 timeline row で使える) |
| 2 択 / 多択 | `op: if` (`condition`, `then`, `else`)、`op: choose` (`cases: [{when, do}]`, `else`)、`op: weighted_branch` |
| 回数・段階 | `op: counter` + CEL `counter("key")`、`op: delay` (`after`, `do`) |
| 受けた攻撃に反応 (parry) | `op: window` |

### `window` (event window)

```yaml
- id: parry
  op: window
  listen: incoming_hit        # 今は incoming_hit のみ (caster が受ける他者の攻撃)
  ticks: 30                   # 窓の長さ。窓が開いている間 cast は終わらない
  absorb: false               # true: ダメージを 0 にする (scale: 0.5 なら 50%)。既定は素通し (v1 counter と同じ)
  once: true                  # 既定 true: 最初に条件を満たした 1 発で窓を閉じる
  filter: "= dist(caster, victim) < 3.5"   # この式が true の hit だけ対象。victim = 攻撃者、incoming.amount = ダメージ量
  do:                         # 対象になった hit ごとに 1 回、次 tick に victim=攻撃者 で実行 (damage / delay / status ...)
    - {id: riposte, op: delay, after: 7, do: [{id: cut, op: damage, target: victim, components: [{kind: physical, amount: 30}]}]}
```

hit は ダメージパイプライン (`ApplyDamageUseCase`、friendly-fire 判定の後) から cast に届く。窓が無効化した hit は
`TargetImmune` で戻り、knockback / kill event は発生しない。

### CEL helpers

| 関数 | 意味 |
|---|---|
| `nearest_enemy_dist(ref)` | `ref` から最寄りの敵の足元位置までの距離 (v1 `distance_less_than` と同じ測り方)。敵がいなければ非常に大きい値 |
| `enemy_in_cone(ref, range, half_angle_deg)` | `ref` の前方 (水平) の扇形内に敵がいるか |
| `hp_ratio(ref)` | `caster` / `victim` の HP 割合 0..1 (不明は 1) |
| `dist(a, b)` / `counter("key")` | 既存 |

`window` は gameplay 専用 (presentation には置けない)。Schema: `content/schemas/skill.schema.json` の `op_window`。
