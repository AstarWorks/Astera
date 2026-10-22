# Haute Couture::The Crimson God of Le Rouge Sinclair / EGO

状態: wiki由来・原動画発動未確認

原資料: [フレーム解析](/reference/crimson-chain-axe/ego.json) / [kit](/reference/crimson-chain-axe/kit.md)

| source seconds | source frame | game tick | range |
|---:|---:|---:|---|

1 tick = 50 ms。量子化は最近傍、半tickは切り上げ。原資料記載の接触遮蔽・コイン対応の不確実性は保持。旧intentは履歴資料であり、新しい再現結果ではない。

## round 2 実装と検証

通常形態を素手からの赤い針へ変更。S3は表示専用の追加投射体で連射を描き、ダメージ付き投射体は3発に限定。

比較用接触tick: 原動画の攻撃接触なし。源のコイン対応にある不確実性は保持する。

状態: 現版撮影あり。原動画でこの操作は未観測。wiki由来のAstera向け設計であり、原作再現の合格対象に含めない。

- 内容SHA-256: `853d917b00800474935e0b55792caf596a440614f2d96730ecd8a02980a26af7`
- 参照SHA-256: `e3388f76d75f8c9de12b686f53219c2cd841995dc4de14058c58bc00811c02df`
- 修正反復: 2 / 最大4（同版の撮影停止による再撮影は別記）
- 撮影: `build/vfx-shot/crimson-chain-axe-ego/compare/`、標的HP: 200.0f
- [共通起点の比較画像](/evidence/crimson-chain-axe-ego.png)
- [全操作の記録](/report/crimson-chain-axe)

強制発動の撮影は資源・クールダウン・入力の証明ではない。HP減少は10%・2倍のランダムクリティカルの影響を受け、実ヒット数と読み替えない。
