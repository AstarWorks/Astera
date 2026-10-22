# Firefist Gregor / S3

状態: astra2解析済・再制作中

原資料: [フレーム解析](/reference/ember-gauntlet/s3.json) / [kit](/reference/ember-gauntlet/kit.md)

| source seconds | source frame | game tick | range |
|---:|---:|---:|---|
| 55.5 | 1665 | 17 | dash_melee |
| 55.8 | 1674 | 23 | melee |
| 57.2 | 1716 | 51 | beam |

1 tick = 50 ms。量子化は最近傍、半tickは切り上げ。原資料記載の接触遮蔽・コイン対応の不確実性は保持。旧intentは履歴資料であり、新しい再現結果ではない。

## round 2 実装と検証

突進接触と炎の噴射を分け、S2を拳の2段へ訂正。大きなvanilla炎粒子を小さな火の粉に変更。

比較用接触tick: [17, 23, 51]。源のコイン対応にある不確実性は保持する。

状態: 現版撮影あり。身体のひねり・腕の構え、炎の連続した面、カメラの追従と瞬間的な寄りが不足。防御の反撃は被弾条件ではなく観測時刻に沿う固定発動。

- 内容SHA-256: `ebe4816e263c42db52a47718e88b1586f71e76336e65f3f8e16a5ff21e16188c`
- 参照SHA-256: `e19f5c72d2cc01bf3c3b2c28b38a96d8019ca69b41ad4de076574d605cd97bfd`
- 修正反復: 2 / 最大4（同版の撮影停止による再撮影は別記）
- 撮影: `build/vfx-shot/ember-gauntlet-s3/compare/`、標的HP: 175.4f
- [共通起点の比較画像](/evidence/ember-gauntlet-s3.png)
- [全操作の記録](/report/ember-gauntlet)

強制発動の撮影は資源・クールダウン・入力の証明ではない。HP減少は10%・2倍のランダムクリティカルの影響を受け、実ヒット数と読み替えない。
