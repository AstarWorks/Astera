# Understanding: model couture-s2-body (Couturier S2 shears, body piece)

Filled from `docs/contributing/understanding/thing.md`. Scope: only the body piece and its two blade partners; the rest of S2 is in `round.md`.

## 物の台帳
| # | 物 | 数 | 由来 (何から出る) | 付く先 | 色 | 大きさ | 出現 → 消える | 動き | 媒体 | 根拠 (anchor confirmed at frame X) |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 鋏の本体 (花のピボット + 輪の持ち手)。刃を持たない | 1 | 手に持つ鋏 `couture-rapier` が t20 で隠れ、頭上 t22 で 3 つに分かれたうちの 1 つ | 空中 (頭上) → 飛翔体 → 敵に刺さる → 手へ戻る | 暗い紫 (刃と同じ素材色) + 花飾りは `couture-rapier` のまま | 約 1 block 角 (`couture-rapier` と同じ座標系・同じ縮尺) | t22 → t57 (頭上 t22-34、飛翔 t35-39、刺さる t40-52、戻り t51-57) | 頭上で回りながら上がる → 敵へ直進 → 刺さって止まる → 糸で手へ引かれる | 3D (item model) | 創始者確認の台帳 (coordinator 2026-10-27): 「頭上で鋏が 3 つに分かれる: 花のピボットのある本体 + 別々の刃 2 本」。参照 frame は未確認 (founder の確認が根拠) |
| 2 | 刃 A (`couture-rapier-blade-a`) | 1 | 同上 | 同上 | 暗い紫 + 銀の刃先 | 刃の長さ約 1 block | 同上 | 本体の左 (-x) 横に並んで飛ぶ | 3D (item model) | 同上 |
| 3 | 刃 B (`couture-rapier-blade-b`) | 1 | 同上 | 同上 | 同上 | 同上 | 同上 | 本体の右 (+x) 横に並んで飛ぶ | 3D (item model) | 同上 |

組み合わせ条件: 本体 + 刃 A + 刃 B を同じ座標・同じ向きで重ねると、手に持つ `couture-rapier` (108 要素) と同じ 1 本の鋏の形に戻ること。

## 作らないもの
| 物 | 理由 |
|---|---|
| 本体の中の刃 (`couture-shears-linked` に含まれる刃 2 本) | 刃は別物 (刃 A / 刃 B) として飛ぶ。本体に刃があると敵の所で刃が 4 本に見える |
| 糸 (桃色の線) | ワールドの線 (target_line)、モデルではない |

## 出来事の順
1. t20: 手の鋏 `couture-rapier` を隠す (rig の weapon_model hidden)。
2. t22-34: 本体と刃 2 本が頭上で別々の物として上がる (行 1-3)。
3. t35-39: 3 つとも別々の飛翔体として横並びで敵へ飛ぶ。
4. t40-52: 3 つが敵に刺さる。
5. t51-57: 糸で手へ引き戻され、t57 に手の `couture-rapier` が戻る。

## エンジン対応
| 台帳 # | primitive | 備考 |
|---|---|---|
| 1 | rig item (頭上) + 実投射体 `couture-rapier-s2-shears` (flight/stuck VFX) | item_model = 本体の新モデル。現状は `couture-shears-linked` を仮置き |
| 2 | rig item + 実投射体 `couture-rapier-s2-blade-a` | item_model `astera:couture-rapier-blade-a` |
| 3 | rig item + 実投射体 `couture-rapier-s2-blade-b` | item_model `astera:couture-rapier-blade-b` |

持つ鋏の決定: 手に持つ武器は `couture-rapier` (1 本の鋏) のまま。t20 で隠し、頭上の分裂 (t22) で 3 つの物に入れ替える。3 つが組み合わさって 1 本の鋏に見える形は、分裂する瞬間 (t22) の見た目で担保する。

## 分からないこと
- 本体にどこまで含めるか: 花のピボット (hub / hub_diamond / stud / petal / heart / bead) と輪の持ち手 (ring / trim / neck / wool) を本体、刃 (blade / ridge / edge / tip / edge_base) を刃 A/B と想定した。この分け方が参照の見た目と合うかは未確認 (founder / coordinator が決める)。
- 本体が 1 つの物か (founder の台帳は 1 つ)。輪の持ち手が刃側に付く案もある。
- 参照映像で分裂の瞬間の本体の見た目を確認していない。
