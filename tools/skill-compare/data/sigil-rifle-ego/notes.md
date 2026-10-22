# Fell Bullet Yi Sang / EGO

状態: wiki由来・原動画発動未確認

原資料: [フレーム解析](/reference/sigil-rifle/ego.json) / [kit](/reference/sigil-rifle/kit.md)

| source seconds | source frame | game tick | range |
|---:|---:|---:|---|

1 tick = 50 ms。量子化は最近傍、半tickは切り上げ。原資料記載の接触遮蔽・コイン対応の不確実性は保持。旧intentは履歴資料であり、新しい再現結果ではない。

## round 2 実装と検証

24fpsの参照を保持。S1は銃を使った近接、S2は実投射体2発、S3は発砲と標的側の紋章へ訂正。

比較用接触tick: 原動画の攻撃接触なし。源のコイン対応にある不確実性は保持する。

状態: 現版撮影あり。原動画でこの操作は未観測。wiki由来のAstera向け設計であり、原作再現の合格対象に含めない。

- 内容SHA-256: `ad5eaf7223e2472cd5d022973fa0b8173f74980df3f6a21d9d02b7edc15f0ab4`
- 参照SHA-256: `d1fc7a5208ea5745693b1d18e13a497e0f8053cb77babb43950aea336b0031a0`
- 修正反復: 1 / 最大4（同版の撮影停止による再撮影は別記）
- 撮影: `build/vfx-shot/sigil-rifle-ego/compare/`、標的HP: 174.416f
- [共通起点の比較画像](/evidence/sigil-rifle-ego.png)
- [全操作の記録](/report/sigil-rifle)

強制発動の撮影は資源・クールダウン・入力の証明ではない。HP減少は10%・2倍のランダムクリティカルの影響を受け、実ヒット数と読み替えない。
