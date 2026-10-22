# The Thumb East Soldato II Sinclair / S2

状態: astra2解析済・再制作中

原資料: [フレーム解析](/reference/vendetta-rifle/s2.json) / [kit](/reference/vendetta-rifle/kit.md)

| source seconds | source frame | game tick | range |
|---:|---:|---:|---|
| 15.375 | 369 | 3 | dash_melee |
| 16.625 | 399 | 28 | dash_melee |

1 tick = 50 ms。量子化は最近傍、半tickは切り上げ。原資料記載の接触遮蔽・コイン対応の不確実性は保持。旧intentは履歴資料であり、新しい再現結果ではない。

## round 2 実装と検証

参照主体をThumb East Soldato II Sinclairへ訂正。銃剣の2/2/3段と防御・Shift+Qのリロードを対応づけた。

比較用接触tick: [3, 28]。源のコイン対応にある不確実性は保持する。

状態: 現版撮影あり。銃を抱える姿勢、腰の回転、踏み込みと逆方向の振り、接触時の寄りが不足。装備モデルと演出用の銃が二重に見える区間がある。

- 内容SHA-256: `a307e3d36f17a95daced7cab06040eb3128837bf2050d4ec093c7ca36acd851a`
- 参照SHA-256: `9f2e27b597c88773b44a4033437beb19a75bfc0a00d5d8e23447de983f179282`
- 修正反復: 1 / 最大4（同版の撮影停止による再撮影は別記）
- 撮影: `build/vfx-shot/vendetta-rifle-s2/compare/`、標的HP: 183.272f
- [共通起点の比較画像](/evidence/vendetta-rifle-s2.png)
- [全操作の記録](/report/vendetta-rifle)

強制発動の撮影は資源・クールダウン・入力の証明ではない。HP減少は10%・2倍のランダムクリティカルの影響を受け、実ヒット数と読み替えない。
