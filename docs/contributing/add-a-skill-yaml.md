# Adding a Skill

Limbus参照武器の制作・修正は [統一手順書](limbus-skill-reproduction.md) から始める。このページはYAML記述のリファレンス。

Astera の Skill は `content/skills/<id>/skill.yaml` 1 ファイルで完結します (`apiVersion: astera/v3`, `kind: Skill`)。
Item とは別ファイルに分かれており、Item の `skills:` 配列から id 参照で複数の Item に同じ Skill を再利用できます。
Kotlin コードは触りません。

v1 / v2 の書き方 (`archetype` / `params` / `phases`、`window` / `look.yaml` 等) は存在しません。文法の正本は
[ADR-0303](../adr/0303-natural-skill-schema.md)、フィールドの形は `content/schemas/skill.schema.json`。

## 最小例

`content/skills/shield-brace/skill.yaml` (抜粋):

```yaml
apiVersion: astera/v3
kind: Skill
id: shield-brace
item: shield-bulwark
meta: {name: {ja: "防御姿勢", en: Brace}, cooldown_ticks: 180, resource_cost: 0}
anchors:
  - {id: chest, anchor: caster, offset: {up: 1.0}, mode: follow}
machine:
  start: cast
  states:
    cast:
      for: 1
      then: end
      do:
        - {id: buff, op: status, target: caster, status: poise, stacks: 1, for: 160}
        - {id: snd, op: sound, sound: item.shield.block, anchor: caster, volume: 1.0, pitch: 1.2}
        - {id: fx, op: particle_def, def: hit-spark, anchor: chest}
    end: {final: true}
```

## 読む順

- 時間 (`at`, `for`, `every`, `until`)、anchor、law: ADR-0303 の 0〜3 節。
- ゲームプレイ op (`hit`, `dash`, `projectile`, `body`, `lock`, `held_model`, ...): 10.1b 節。
- ロード時の静的検査: 10.4 / 11.4 節。違反は名前付きの load error になり、黙って捨てられることはない。
- 点表 (武器の point / axis): `content/frames/<item>.yaml` (`apiVersion: astera/v3`)。
- 体の手などの点: 共通骨格 `content/frames/body.yaml` (`points:`)。クリップ (`content/animations/<anim>.yaml`) には点を書かない (rev4.9 G6)。

## 回転の符号 (ADR-0303 section 0)

`deg` は名前付き軸まわりの回転で、正の `deg` は基底軸を **forward -> left -> up -> forward** の順に送る
(`+deg about up` は forward を left へ、`+deg about left` は up を forward へ、`+deg about forward` は left を up へ)。
時計回り / 反時計回りという語は使わない (視点が要るため)。vanilla の `yaw` は skill に書けない (`yaw = -deg about up` はエンジンが適用する)。

| 欲しい動き | 書き方 |
|---|---|
| 水平斬り: 先端が右から左へ (10 tick で 120 度) | `{law: spin, axis: up, deg_per_tick: 12, for: 10, start: [{axis: up, deg: -60}]}` |
| 振り下ろし: 先端が真上から正面へ (6 tick で 90 度、刃の初期姿勢は up) | `{law: spin, axis: left, deg_per_tick: 15, for: 6}` |
| ドリル突き: 刃が自分の長さ軸まわりに回り、刃先が left から up へ (8 tick で 1 回転) | `{law: spin, axis: forward, deg_per_tick: 45, for: 8}` |

逆チェック: 書いた符号で先端が逆へ動くなら `deg` の符号を反転する。軸は入れ替えない。

## Item からの利用

```yaml
# content/items/cleaver.yaml
skills:
  - trigger: left_click
    skill_id: cleaver-chop
```

同じ Skill を別 Item に異なる trigger で bind できます。

## rev4.10 の文法 (ADR-0303 rev4.10)

手本は `content/skills/frost-shard` (projectile / bounce / callee)、`volley-demo` (groups / every / frames / fragments / let)、
`sanctuary-ward` (area / heal / laws)。新しい skill はこの型から書く。下の各項は ADR-0303 rev4.10 の要点と最小の例。

### CEL の数学関数

`pi` (定数。大文字 `PI` も残る)、度単位の `sind` `cosd` `tand` `asind` `acosd` `atan2d`、`mod(a, b)` (= `a - b * floor(a / b)`、符号は `b` に従う。`mod(-1, 3) = 2`)、`fract(x)`。すべて pure。
`%` は整数専用なので、実数の剰余は `mod(a, b)` を書く。

```yaml
offset: "= vec(cos(i * 2 * pi / n), sind(i * 30), 0.0)"
```

### `let:` (局所の束縛)

skill / part / state / op の map に `let:` を置ける。値は数・bool・`${param}`・`"= 式"`。
束縛名は `= ` 文字列の中でだけ使われ、ロード時に `(値)` として置換される (実行時には存在しない)。
名前は素の識別子で、param・組み込み (`i` など)・関数名・op / anchor / frame の id・外側の let と同じにはできない。
タイミング欄で使うと、置換後の式でクラス判定される (C5 の load error は let 名を挙げる)。

```yaml
let:
  petals: 6
  petal_step: "= 360 / petals"
# ...
offset: "= vec(cos(i * 2 * pi / petals), sind(i * petal_step), 0.0)"
```

### `drag` (spin / move)

`drag: f` (`0 < f < 1`) は速さを 1 tick ごとに `1 - f` 倍する減衰。`for` が必須。`ease` と `accel` とは同時に書けない。

```yaml
- {law: move, velocity: {up: 0.4}, drag: 0.3, at: 0, for: 6}
- {law: spin, axis: up, deg_per_tick: "= 8 * (i + 1)", drag: 0.15, at: 0, for: 16}
```

### `align` の blend

`align` の向き先を `to: world.up` などにしたうえで `ease` を付けると、窓の始めの向きから目標へ slerp で寄る。
`for` が必須で、`step` とは併用できない。`ease` が無ければ従来どおり即座に向く。

```yaml
- {law: align, axis: forward, to: world.up, up: caster.forward, at: 4, for: 6, ease: out_quad}
```

### skill-local `fragments:` と `use: fragment:`

skill 文書の top-level `fragments:` に、一つの op 列 (`do:`、`machine:` は書けない) を名前付きで置く。
state の `do:`、group の `do:`、`on.hit` / `on.miss`、transition の `do:` のどこからでも `use: fragment:<name>` で呼べる。
fragment は `frames_in` と `params` を持てる。循環呼び出し、存在しない名前は load error。

```yaml
fragments:
  burst:
    params: {power: {type: number, default: 1}}
    frames_in: {origin: anchor}
    do:
      - {id: dmg, op: damage, target: {kind: enemies, around: origin, radius: 2.5, max_count: 8, include_self: false}, damage: {fire: "= 6 * ${power}"}}
# 呼び出し側 (ball の on.hit の中)
- {op: use, use: "fragment:burst", with: {power: "= 2 + parent.i"}, frames: {origin: contact}}
```

### `frames_in` (呼ばれる側の frame)

`frames_in: {<name>: <anchor>}` を callee (skill / fragment) に書くと、`<name>` は anchor・`to`・`shift` の軸・
`damage` / `status` の `around`・`use` の `frames:` で使える点になる。呼ぶ側は `frames: {<name>: <caller の frame>}` で束縛する。
束縛されていない `frames_in` は load error で名前を挙げる。

```yaml
# callee: content/skills/frost-shard/skill.yaml の 2 つ目の文書 (id: frost-shard-burst)
frames_in: {spot: anchor}
# ... particle の anchor: spot
# caller
- {id: burst, use: skill:frost-shard-burst, with: {snow: 30, rod: 10, dust: 20}, frames: {spot: heart}}
```

### `stretch` law (2 点間の伸び)

`{law: stretch, axis: <軸>, base: <点>, tip: <点>, for: N, step: k}` は display を base から tip へ伸ばす。
位置 = base、回転 = 軸が base から tip を向く向き (ゼロ長なら直前の回転を保つ)、スケール = その軸方向の距離。
`base` / `tip` は組み込み (`caster`, `caster.eye`) か skill 宣言の anchor。

```yaml
- {id: spine, op: display, kind: sprite, sprite: line-thin, anchor: ground, size: [0.6, 1.0], billboard: vertical, at: 0, for: 40,
   motion: [{law: stretch, axis: up, base: ground, tip: sky, for: 40, step: 4}]}
```

### `max_count` に param

`target` と `hit` の `max_count` は `"${cap}"` を受ける。ロード時に param の default で畳む。
値は 1 以上の整数か `all` (省略と同じ、無制限)。default が 1 未満、または未宣言の param は load error。

```yaml
params:
  cap: {type: number, default: 3}
# ...
- {id: smite, op: damage, target: {kind: enemies, around: ground, radius: "${radius}", max_count: "${cap}"}, damage: {electric: "${damage}"}, at: 10}
```

### `absorb` (hurt transition と `on.absorbed`)

`absorb: true` は `hurt` / `hurt.<kind>` の transition にだけ書ける (他の event や `absorb: false` は load error)。
受けた hit は取り消され (HP・ノックバック・被弾音なし)、そのあと transition が走る。

```yaml
states:
  guard:
    for: 30
    then: done
    on:
      - {event: hurt, when: "= dist(caster, event.attacker) <= 3.5", absorb: true, to: riposte,
         do: [{id: riposte_hit, op: damage, target: event.attacker, damage: {physical: "= dmg_physical"}, at: 7}]}
```

攻撃側から見ると、absorb された hit は外れ扱い。ダメージも `on.hit` も無く、hit 数にも数えない (`max_count` の枠は消費する)。
代わりに攻撃側 op の `on.absorbed` が走る (`event.victim` が読める。hit と同じ hit-scope 規則。`hit` と `projectile` で有効)。
何も当たらなかった場合の `miss` は従来どおり op の終わりに出る。

### `victim.center` と anchor の `mode`

`victim.center` / `caster.center` / `target.center` は (足 + 目の高さ) / 2 を毎 tick 読む点。目の高さを持たない対象 (ブロック、アーマースタンド) では hitbox の中心。
anchor の `mode: follow | snapshot` は必須 (隠れた既定値は無い)。`offset` は数・`${param}`・`= CEL` を受ける。`mount` は無い。

```yaml
anchors:
  - {id: muzzle, anchor: caster.eye, offset: {forward: 0.5}, mode: snapshot}
  - {id: heart,  anchor: victim.center, mode: follow}
```

## 共有 part で武器を書く (`content/skill-parts`)

新しい武器の YAML には、その武器固有の値だけを書く。型の決まった塊は `content/skill-parts/<id>.yaml` の part を
`use: <id>` で呼ぶ (`with:` が param、`frames:` が `frames_in` の束縛)。part の先頭コメントに呼び出し例がある。

| part | 何を出すか | 主な param |
|---|---|---|
| `strike-impact` | 矢 / ナイフ / 銛の着弾: 音 + `hit-spark` + 粉 (dust) | `snd`, `color` (+ `vol` `pitch` `size`) |
| `ring-impact` | 属性弾の着弾: 水平リング 16 + 上向きリング 8 + 中心 pop | `particle`, `pop`, `pop_count`, `ring_speed`, `up_speed` |
| `parry` | ガード窓と受け流し | `window`, `arc`, `range`, `recover`, `whiff_lag` |
| `jagged-line` | 前方へ揺らぐ電光の線 | `length`, `jitter`, `hold` |

どの archetype も、弾 / 判定 (`projectile` or `hit`) と自分の `on.hit` / `on.block` は skill に書き、着弾の見た目だけを part に渡す。

```yaml
# 弓・投擲 (shortbow-arrow の on: 部分)
on:
  hit:
    - {id: dmg, op: damage, target: event.victim, damage: {physical: "${dmg_physical}"}}
    - {id: hit_fx, use: strike-impact, with: {snd: "astera:weapons/blade-hit", color: "#bea064", size: 0.9}, frames: {spot: heart}}
  block:
    - {id: block_fx, op: particle_def, def: hit-spark, anchor: contact.point}
```

```yaml
# 魔法の弾 (ember-rod-spark の on: 部分)。hit と block で同じ part を contact 違いで呼ぶ
on:
  hit:
    - {op: use, use: ring-impact, with: {particle: flame, pop: lava, pop_count: 5, ring_speed: 0.25, up_speed: 0.2}, frames: {spot: heart}, detach: true}
  block:
    - {op: use, use: ring-impact, with: {particle: flame, pop: lava, pop_count: 5, ring_speed: 0.25, up_speed: 0.2}, frames: {spot: contact}, detach: true}
```

```yaml
# 銃 (pistol-shot): 弾道線は共有 skill gun-tracer を `use: skill:` で呼び、着弾は skill-local fragment
- {id: tracer_h, use: skill:gun-tracer, with: {thickness: 0.1}, frames: {muzzle: muzzle, tip: contact}}
- {id: fx_hit, op: use, use: "fragment:impact", frames: {spot: heart}}
```

```yaml
# 近接 (bastion-greatsword-def): 受け流しは state ごと part。part の終わりを event で受ける
guard:
  use: parry
  with: {window: 16, arc: 110, range: 4, recover: 4, whiff_lag: 1}
  frames: {weapon: shield}
  on:
    - {event: brace/guard.end, to: held}
```

part を足す時は、呼び出し側の op id (`hit_fx` など) と part 内の id がそのまま `skill-oracle` の `op=` に出る点に注意する。
part 化で既存 skill の行を変えない条件: 呼び出し id を元の fragment 呼び出しと同じにし、part 内の id も同じにする。
`projectile` 本体、発射口の音 + 煙、軌跡の rider (`until: bolt.end`) は呼び出し id が op path の前に付くので part にできない (engine gap)。

## 参考

- [ADR-0303 Natural skill schema](../adr/0303-natural-skill-schema.md)
- [ADR-0301 Skill engine v2 frame tree](../adr/0301-skill-engine-v2-frame-tree.md) (一部 supersede)
- [[contributing/add-an-item-yaml]]

## v3 motion law の数値文法 (ADR-0303 3.4 / 3.5)

`display` / `frame` / `projectile` の `motion:` に書く law の数値は、ロード時に評価して展開表へ焼く。

- **CEL と copy index**: 数値の代わりに `"= 10 + i * 2"` と書ける。読めるのは定数・param・copy index (`i`, `n`, `parent.i`) だけ。
  `times: N` の family は copy ごとに評価して焼く (copy k は `i = k`)。`frame` / `projectile` の `motion` も同じで、
  `anchor: "blade[1]"` で乗る rider は copy 1 の law を受け取る。
  `t` / live 値 (`nearest_enemy_dist(..)` など) / `rand` は焼けないので、値の名前を挙げた load error になる。
- **`grow` の `prop: offset` と `accel`**: `{law: grow, prop: offset, per_tick: {forward: 1.0}, accel: {forward: -0.1}, for: 4}`。
  位置 = `start + per_tick * tau + accel * tau^2 / 2` (start 省略時は 0)。`accel` が 0 でなければ 1 tick ごとに key を出す。
- **三つ組**: `keys.scale` と `grow.to` / `grow.start` は `[left, up, forward]` の 3 要素 list でも書ける
  (`{left: .., up: .., forward: ..}` の map と同値)。要素数が 3 以外は load error。
- `motion` の値に CEL 文字列を書いても、family の per-index list とは見なされない (`"= ..."` は一つの数値式)。

### 被弾側 (victim side): `react` と `hit.attach`

```yaml
- {id: pose, op: body, target: caster, anim: bastion-s1-body, speed: 1.0, for: 12}   # body は caster 専用 (被弾者は react)
- {id: r, op: react, target: event.victim, flinch: true, push: {back: 0.4, up: 0.1}, hold: 3, player: {anim: bastion-s1-body}, for: 6}
  # on.hit 内。flinch=バニラ被弾モーション / push=術者から離れる向き / hold=移動拘束 tick (ゲームプレイ効果、整数、<= for) / player.anim は player 被弾者のみ (mob はスキップ)。for 必須
- id: harpoon
  op: projectile
  hit: {targets: enemies, once: true, attach: {for: 20}}   # attach 必須 (false か {for: N})。true は load error
  block: {mode: stick, for: 20}   # attach: {for: N} は once: true と block.mode: stick が必要 (block.for は壁に刺さる tick 数)
  on: {stick: [...], unstick: [...]}   # 命中で stick、attach.for tick 後 (被弾者が先に消えれば即) に unstick して end
```

刺さった姿勢は命中時点の被弾者 feet 基底 (feet 原点 + yaw) で保存され、被弾者の位置と yaw に追従する。
