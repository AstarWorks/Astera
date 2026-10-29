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
  absorb: true                # true: 受けた hit を無効にする (ダメージ 0、scale: 0.5 なら 50%)。既定 false = 素通し。parry (couture-rapier-def) は true (SKL-15)
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
| `nearest_enemy_dist(ref)` | `ref` から最寄りの敵までの距離。**足元 (feet) から足元 (feet) まで**で、hitbox の表面ではない。敵がいなければ非常に大きい値 |
| `enemy_in_cone(ref, range, half_angle_deg)` | `ref` の前方 (水平) の扇形内に敵がいるか |
| `dist(a, b)` / `counter("key")` | `dist` も entity ref (`caster` / `victim` / `enemy`) は feet to feet。frame / point は原点間 |

距離はすべて feet to feet (SKL-15): `< 3.6` は「足元が 3.6 ブロック未満」。身長や hitbox 幅は効かない。

`window` は gameplay 専用 (presentation には置けない)。Schema: `content/schemas/skill.schema.json` の `op_window`。

## v2 演出の文法 (`look.yaml` presentation, SKL-14)

v1 の演出 (rig / sprite / trail / 繰り返し emit) と同じ見た目を v2 で書くための field。値の意味は v1 と同じ。

### Pose の時間 (読み方だけ、書く field は無い)

`track` の keyframe `t: N` は **tick N の画面にそのまま出る**。クライアントは pose を 1 tick かけて補間するので、
engine は 1 tick 先の値を送る (tick k に push した pose が k+1 に見える)。keyframe を 1 つ前倒しで書く必要は無い。

### Rig: `mesh` の `pivot` / `rest` / `tilt`

武器を持つ rig を作るときの `mesh` op の field。合成順は v1 と同じ `pivot フレーム ∘ tilt ∘ track(swing) ∘ rest ∘ part offset`。

| field | 型 | 意味 |
|---|---|---|
| `pivot` | `[left, up, forward]` (blocks) | rig の回転中心を frame 原点からずらす。swing はこの点の周りで回る |
| `tilt` | `[yaw, pitch, roll]` (度) | swing 全体を傾ける。track の回転より外側 |
| `rest` | `[yaw, pitch, roll]` (度) | 武器を持った静止姿勢。track の回転より内側。v1 `rest: [0, 90, 0]` は刃先 (+Y) が前方を向く |

角度は `track.rotation` と同じ yaw/pitch/roll。どれも省略 = 0。

```yaml
- {id: blade, op: mesh, frame: rig, item: couture-rapier, pivot: [-0.3, 1.1, 0.3], rest: [0, 90, 0], tilt: [0, 0, 20]}
```

### `plate` の flipbook と色

| field | 意味 |
|---|---|
| `frame_ticks` | 1 frame を何 tick 見せるか (既定 1、1 frame の sprite では無効) |
| `loop` | true = 最後の frame の次に先頭へ戻る、false (既定) = 最後の frame で止まる |
| `color` | `"#RRGGBB"` の色味。sprite の色に掛ける |

### `trail` の `fade` / `style`

`fade: p` は alpha を `alpha * (1 - age/ticks)^p` で落とす (既定 1。小さいほど長く残り、0 で最後まで落ちない)。
`style: soft | crisp | both` は縁の質感 (既定 soft)。`crisp` は端まで alpha を保つ。

多層 ribbon (v1 の trail と同じ形。root から tip までの半径方向の帯):

| field | 意味 |
|---|---|
| `bands` | `[{from, to, color: "#RRGGBB", alpha, color_to?}]`。`from`/`to` は root=0 から tip=1 の割合。`color_to` を書くと古くなるほどその色へ寄る |
| `edge` | `{color, width}`。tip 側の縁取り (幅は block 単位、既定 0.04) |
| `dark_rim` | `{color, alpha, width}`。tip の外側の暗いふち (既定 width 0.18) |
| `step_deg` | 5..90 (既定 30)。刃の回転がこの角度を超えたら ribbon を最大 12 分割して滑らかにする |
| `lag_ticks` | 扇を N tick 遅らせて描く (既定 0) |
| `double_sided` | true で裏面にも同じ quad を張る (text quad は片面のため) |

```yaml
- {id: fan, op: trail, of: blade_tip, root: blade_root, ticks: 9,
   bands: [{from: 0.0, to: 1.0, color: "#ff8f18", alpha: 0.63, color_to: "#e04d08"}],
   dark_rim: {color: "#2a170c", alpha: 0.69, width: 0.08}, step_deg: 36}
```

### `projectile` の block 挙動

`block_mode` で弾が block に当たったときの挙動を選ぶ。既定 `despawn` (v1 と同じ)。`on: block` の handler は接触のたびに走る
(`pass` を除く)。

| mode | 挙動 |
|---|---|
| `despawn` | 当たったら弾を終える |
| `stick` | 接触点に飛行方向のまま刺さり、`stick_ticks` (既定 60) 後に消える。消えるとき `stick_end` event |
| `bounce` | 法線で速度を反射して `restitution` (既定 0.6) を掛ける。`max_bounces` (既定 3) 回まで、その次の接触で終わる |
| `pass` | block を無視する (`on: block` も走らない) |

### 繰り返し: timeline の `for`

`every: N` の row は `until:` (event) まで繰り返す。`for: M` は **最初に出た tick から M tick で打ち切る**
(終端は含まない: `every: 2, for: 10` は 5 回)。`for` は `every` と一緒にしか書けない。v1 の `start..end` を回す
particle element / rig emitter はこの形に変換される。

```yaml
- {id: embers, at: 17, every: 1, for: 8, do: [{id: e, op: emit, at: staff_tip, particle: orrery-ember, count: 1}]}
```

## v2 SKL-15 の決定 (founder 2026-10-28)

### `emit` は vanilla の `spawnParticle` そのもの

vanilla particle の `emit` は `spawnParticle(count, offset x/y/z, extra, data)` に **1:1** で渡る。隠れた既定値は無い。

| field | vanilla 引数 | 未指定 |
|---|---|---|
| `count` | `count` | 1 |
| `offset: [x, y, z]` | `offsetX/Y/Z` | `[0, 0, 0]` |
| `speed` | `extra` | 0 |
| `data` / `data_size` | `data` (dust の色 `"#rrggbb"` と大きさ) | なし |

vanilla の `count: 0` はそのまま効く (`offset` が向き、`speed` が速さ)。`dir` と shape だけが **明示 sugar**:

- `dir: <vec3>` (+ `spread_deg`, `inherit_velocity`, `speed`) = `count` 個の spawn に展開される。各 spawn は `count 0`、
  `offset = jitter(dir, spread_deg) * speed + inherit_velocity`、`extra 1`。`dir` を書かない emit には何も足されない。
- custom particle (`content/particles/<id>.yaml`) の `count` は **粒の数** (vanilla と同じ。定義全体の繰り返しではない)。
  未指定 = 定義自身の count。shape の向きは `dir`、無ければ `look(caster)` (pitch 込みの視線)。

### 繰り返しの tick

repeat する `sound` (`every`) / `emit` row (`every`) は `at`, `at+N`, `at+2N`, ... の tick ちょうどに出る。

### `hit` の sector は目の高さから pitch に沿う

`sector: {frame: caster, at: eye, forward: caster.forward, left: caster.left, angle_deg, range, height}`。`at: eye` で扇の頂点を目に置き、
`caster.forward` は pitch 込みの視線なので扇は見上げ / 見下ろしに追従する。`angle_deg` = 扇の開き、`range` = 半径、
`height` = 扇の面に垂直な **スラブの全厚み** (面を中心に上下 height/2)。`at` 省略 = frame 原点 (足元)。

### `aim` frame と投射物の muzzle

builtin frame `aim` = 目の位置 + yaw/pitch 込みの向き。offset は `[left, up, forward]` で、`forward` は視線方向 (pitch 込み)。
投射物の `from` は `aim` 親の point にする: `{id: pt, kind: point, parent: aim, offset: [0, 0, 0.5]}`。`caster` frame は yaw のみ
(足元) なので、pitch に従わせたい muzzle / 近接の起点には使わない。

### `freeze` の `enemy` scope

hit handler の `freeze` で `scope: [enemy, caster]` と書くと、当たった相手 (victim) の動きと AI も止まる (v1 `hit_stop` と同じ)。

### `lock` (windup)

`{op: lock, target: caster, speed: 0.0, ticks: 20}` で windup 中に caster を固定する (`speed` = 歩行速度の倍率、0 = 動けない)。
bastion-greatsword-ego の windup がこれ。
