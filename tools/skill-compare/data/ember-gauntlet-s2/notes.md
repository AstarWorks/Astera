# Firefist Gregor / S2

状態: astra2解析済・再制作中

原資料: [フレーム解析](/reference/ember-gauntlet/s2.json) / [kit](/reference/ember-gauntlet/kit.md)

| source seconds | source frame | game tick | range |
|---:|---:|---:|---|
| 42.966667 | 1289 | 5 | dash_melee |
| 43.766667 | 1313 | 21 | melee |

1 tick = 50 ms。量子化は最近傍、半tickは切り上げ。原資料記載の接触遮蔽・コイン対応の不確実性は保持。旧intentは履歴資料であり、新しい再現結果ではない。

## round 2 実装と検証

突進接触と炎の噴射を分け、S2を拳の2段へ訂正。大きなvanilla炎粒子を小さな火の粉に変更。

比較用接触tick: [5, 21]。源のコイン対応にある不確実性は保持する。

状態: 現版撮影あり。身体のひねり・腕の構え、炎の連続した面、カメラの追従と瞬間的な寄りが不足。防御の反撃は被弾条件ではなく観測時刻に沿う固定発動。

- 内容SHA-256: `a5cd9c0bdeb223359baeb5d2b97e0979a6c384c3c75b45d8bcbe3953a683b1be`
- 参照SHA-256: `1056817f99d7680adb06fe93fa841f595683f09d84492fd06cd18be871a52ddb`
- 修正反復: 2 / 最大4（同版の撮影停止による再撮影は別記）
- 撮影: `build/vfx-shot/ember-gauntlet-s2/compare/`、標的HP: 182.288f
- [共通起点の比較画像](/evidence/ember-gauntlet-s2.png)
- [全操作の記録](/report/ember-gauntlet)

強制発動の撮影は資源・クールダウン・入力の証明ではない。HP減少は10%・2倍のランダムクリティカルの影響を受け、実ヒット数と読み替えない。
