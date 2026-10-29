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
  - {id: chest, anchor: caster, offset: {up: 1.0}}
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
- 体のクリップと手の点: `content/animations/<anim>.yaml` の `points:`。

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

### 被弾側 (victim side): `body target:` と `hit.attach`

```yaml
- {id: pose, op: body, target: caster, anim: bastion-s1-body, speed: 1.0, for: 12}   # target 必須: caster / event.victim / attacker
- id: harpoon
  op: projectile
  hit: {targets: enemies, once: true, attach: true, attach_to: victim.feet}   # attach 必須 (既定なし)
  block: {mode: stick, for: 20}   # attach: true は once: true と block.mode: stick が必要
  on: {stick: [...], unstick: [...]}   # 命中で stick、block.for tick 後に unstick して end
```

`attach_to` は作者が名前を付ける (`victim` か `victim.<point>`)。組み込みの被弾者 body 点はない。
