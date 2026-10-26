# 武器 TTK 表とバランス帯 (MODE-09)

HP20 のプレイヤーを 1 対 1 で倒すまでの名目 TTK。`python3 scripts/ttk-table.py` が `content/items` と
`content/skills` から計算し、`ShippedWeaponTtkBandsTest` (plugin/combat) が同じ算術で帯を保証する。
実機に近い測定(スポーン→撃破の tick)は `WeaponRosterTtkScenarioTest` (game-sim, 基本 10 本)。

## 計算規則

- 主攻撃 = 左クリックのスキル、無ければ右クリック。Limbus 系は s1。
- 与ダメ = 1 回の発動が 1 人に与える literal ダメージの総和 (`cast_skill` は辿る。シーケンスは全段を 1 回ずつ)。
- プレイヤーは無敵フレーム 20 tick のため直撃は 1 秒に 1 回。間隔 = max(クールダウン, 20)。
  クールダウンはアイテム側 `cooldown_override_ticks` が勝つ。
- 殺すのに要る命中数 = ceil(20 / 与ダメ)、TTK = (命中数 - 1) × 間隔。防具・属性・クリティカルは含めない。
- 出血/燃焼などの継続ダメージと(クリティカル)は実戦の TTK を縮めるだけ。dagger はこれが個性(出血 60%)。

## 表 (2026-10-26)

| weapon | kind | Lv | skill | dmg | cd(t) | interval(t) | hits | TTK(s) | DPS |
|---|---|---|---|---|---|---|---|---|---|
| shotgun | gun | 3 | shotgun-blast | 13 | 26 | 26 | 2 | 1.30 | 10.0 |
| pistol | gun | 1 | pistol-shot | 7 | 6 | 20 | 3 | 2.00 | 7.0 |
| rifle | gun | 2 | rifle-shot | 8 | 14 | 20 | 3 | 2.00 | 8.0 |
| vendetta-rifle | gun | 4 | vendetta-rifle-s1 | 17 | 90 | 90 | 2 | 4.50 | 3.8 |
| sigil-rifle | gun | 4 | sigil-rifle-s1 | 9 | 55 | 55 | 3 | 5.50 | 3.3 |
| thunder-staff | staff | 3 | lightning-bolt | 14 | 70 | 70 | 2 | 3.50 | 4.0 |
| verdant-staff | staff | 3 | thorn-bolt | 5 | 24 | 24 | 4 | 3.60 | 4.2 |
| fire-staff | staff | 2 | flame-burst | 12 | 80 | 80 | 2 | 4.00 | 3.0 |
| frost-staff | staff | 2 | frost-shard | 6 | 30 | 30 | 4 | 4.50 | 4.0 |
| orrery-staff | staff | 4 | orrery-s1 | 16 | 115 | 115 | 2 | 5.75 | 2.8 |
| greatsword | sword | 3 | greatsword-cleave | 13 | 28 | 28 | 2 | 1.40 | 9.3 |
| knight-sword | sword | 1 | sword-slash | 8 | 10 | 20 | 3 | 2.00 | 8.0 |
| dagger | sword | 2 | dagger-stab | 5 | 8 | 20 | 4 | 3.00 | 5.0 |
| bastion-greatsword | sword | 3 | bastion-greatsword-s1 | 16 | 64 | 64 | 2 | 3.20 | 5.0 |
| verdict-hammer | sword | 4 | verdict-hammer-s1 | 17 | 80 | 80 | 2 | 4.00 | 4.2 |
| edict-blade | sword | 4 | edict-s1 | 19 | 90 | 90 | 2 | 4.50 | 4.2 |
| ember-gauntlet | sword | 3 | ember-gauntlet-s1 | 18 | 100 | 100 | 2 | 5.00 | 3.6 |
| gossamer-katana | sword | 3 | gossamer-katana-s1 | 18 | 100 | 100 | 2 | 5.00 | 3.6 |
| couture-rapier | sword | 3 | couture-rapier-s1 | 8 | 55 | 55 | 3 | 5.50 | 2.9 |
| crimson-chain-axe | sword | 4 | crimson-chain-axe-s1 | 7 | 60 | 60 | 3 | 6.00 | 2.3 |

`rtm-blaster` / `skill-tester` は dev 用(下記)で表に含めない。

## 帯と設計意図

| アーキタイプ | TTK 帯 | 意図 |
|---|---|---|
| 銃(基本) pistol / rifle / shotgun | 1.0 - 3.0 s | 射程で住み分ける。shotgun は近距離の 2 発キル(1.3 s)、rifle は長射程 8 ダメで 3 発、pistol は無料の入口(7 ダメ・弾 12)。 |
| 剣(基本) knight-sword / dagger / greatsword | 1.0 - 3.5 s | 近接は射程 2.6-4.2。greatsword は 2 発キルだが energy 35 で連打できない。dagger は直撃が弱い代わりに出血で実戦 TTK を縮める。 |
| 杖(基本) fire / frost / thunder / verdant | 3.0 - 5.0 s | 直撃より状態異常(燃焼/凍結/感電/毒)とエリアで勝つ。単発 TTK は長め。 |
| 看板(Limbus s1, シーケンス) | 3.0 - 6.0 s | s1 は演出重視の遅い基本技。DPS は基本武器以下。本領は s2/s3/def/ego の多段で、s1 の TTK だけ見て弱いとは言えない。 |

どの武器も名目 DPS は 10.5 以下(最大は shotgun の 10.0)。看板 s1 の最大 DPS は基本の銃/剣の最小 DPS 以下。

## この回の調整

- `crimson-chain-axe` s1: 9.0 s → 6.0 s (`cooldown_override_ticks: 60`)。7 ダメ 3 発に 90 tick は帯の外だった。
- `sigil-rifle` s1: 7.0 s → 5.5 s (55)。`couture-rapier` s1: 6.5 s → 5.5 s (55)。
- スキル本体のダメージ・VFX は触っていない(Limbus 系は別トラック)。dagger は直撃 5 ダメで knight-sword (8) に見劣りするが、
  出血込みの実測は 2.45-3.00 s (`WeaponRosterTtkScenarioTest`) で帯内のため据え置き。

## レベル要件 (MODE-03)

`level_requirement` は現状ツールチップ表示のみ(装備・購入のゲートではない)。実際の入手ゲートは GOLD (ショップ 400-600G)。
XP 曲線 `100 * (n-1) * n / 2` (Lv2=100, Lv3=300, Lv4=600, Lv5=1000) と試合報酬 (参加 25 + 敗北 60 / 勝利 150、キル 20) から、
負け 4 試合で約 340-420 XP で Lv3、勝敗混在 6-7 試合 (初日) で Lv4。そこで:

- 400-500G の 4 本 (bastion-greatsword, couture-rapier, gossamer-katana, ember-gauntlet) を Lv4 → **Lv3**。
- verdict-hammer Lv5 → **Lv4**。550-600G の残り 5 本 (vendetta-rifle, sigil-rifle, edict-blade, crimson-chain-axe, orrery-staff) は Lv4。
- Lv5 要件の武器は無くなった。初日の GOLD は約 200-350 (試合 4-6 回 + レベル報酬 125) なので、
  最安の看板 bastion-greatsword (400G) が初日の終わりに届く水準。価格の見直しは economy 側。

## dev / demo content の分離 (MODE-08, ADR-0270)

- アイテム: `pdc_tags: astera:demo: "true"` (`rtm-blaster`, `skill-tester`)。ID 指定の付与(`/astera items give`)・テスト・e2e は従来通り動く。
  管理パネルのカタログ、フルキット、トレーニングラックには出ない。
- スキル: タグ `demo` (`simple-shoot`, `vfx-demo-*`, `mechanics-demo-quake`, `anim-demo-*`)。管理パネルの Skills ページには出ない。
  `/astera skill cast <id>`、`/astera skill list`、game-sim の `caster.cast(...)` は従来通り。
- ショップ・スターター・レベル報酬に載せない(`ShippedWeaponAcquisitionTest` が保証)。
