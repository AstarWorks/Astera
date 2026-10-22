# Haute Couture::The Black Knight of Le Noir Don Quixote / EGO

状態: wiki由来・原動画発動未確認

原資料: [フレーム解析](/reference/verdict-hammer/ego.json) / [kit](/reference/verdict-hammer/kit.md)

| source seconds | source frame | game tick | range |
|---:|---:|---:|---|

1 tick = 50 ms。量子化は最近傍、半tickは切り上げ。原資料記載の接触遮蔽・コイン対応の不確実性は保持。旧intentは履歴資料であり、新しい再現結果ではない。

## round 2 実装と検証

召喚物を剣ではなく釘の軸・頭に変更。S3は釘、突進、ハンマーの順序と接触時刻を訂正。

比較用接触tick: 原動画の攻撃接触なし。源のコイン対応にある不確実性は保持する。

状態: 現版撮影あり。原動画でこの操作は未観測。wiki由来のAstera向け設計であり、原作再現の合格対象に含めない。

- 内容SHA-256: `2d792920242ac15e6b41a5c1248d789185ec1b16457927eea861306c93d8cb6f`
- 参照SHA-256: `6c8fc907061c3cd88a66bab503d1f30f4d28906803292cd67efcb29765b786ed`
- 修正反復: 1 / 最大4（同版の撮影停止による再撮影は別記）
- 撮影: `build/vfx-shot/verdict-hammer-ego/compare/`、標的HP: 152.768f
- [共通起点の比較画像](/evidence/verdict-hammer-ego.png)
- [全操作の記録](/report/verdict-hammer)

強制発動の撮影は資源・クールダウン・入力の証明ではない。HP減少は10%・2倍のランダムクリティカルの影響を受け、実ヒット数と読み替えない。
