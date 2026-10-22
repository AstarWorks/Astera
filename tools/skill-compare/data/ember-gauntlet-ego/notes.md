# Firefist Gregor / EGO

状態: wiki由来・原動画発動未確認

原資料: [フレーム解析](/reference/ember-gauntlet/ego.json) / [kit](/reference/ember-gauntlet/kit.md)

| source seconds | source frame | game tick | range |
|---:|---:|---:|---|

1 tick = 50 ms。量子化は最近傍、半tickは切り上げ。原資料記載の接触遮蔽・コイン対応の不確実性は保持。旧intentは履歴資料であり、新しい再現結果ではない。

## round 2 実装と検証

突進接触と炎の噴射を分け、S2を拳の2段へ訂正。大きなvanilla炎粒子を小さな火の粉に変更。

比較用接触tick: 原動画の攻撃接触なし。源のコイン対応にある不確実性は保持する。

状態: 現版撮影あり。原動画でこの操作は未観測。wiki由来のAstera向け設計であり、原作再現の合格対象に含めない。

- 内容SHA-256: `b369356cf79649491ffee44d8949668459d59d5bffd719b9e8d8c2953e9ac145`
- 参照SHA-256: `2a37f48a7116dba463a74dd110f4218662d8e301e9b3a7efddae98cf8f97dd5d`
- 修正反復: 2 / 最大4（同版の撮影停止による再撮影は別記）
- 撮影: `build/vfx-shot/ember-gauntlet-ego/compare/`、標的HP: 187.21999f
- [共通起点の比較画像](/evidence/ember-gauntlet-ego.png)
- [全操作の記録](/report/ember-gauntlet)

強制発動の撮影は資源・クールダウン・入力の証明ではない。HP減少は10%・2倍のランダムクリティカルの影響を受け、実ヒット数と読み替えない。
