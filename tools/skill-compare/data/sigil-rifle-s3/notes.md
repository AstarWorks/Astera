# Fell Bullet Yi Sang / S3

状態: astra2解析済・再制作中

原資料: [フレーム解析](/reference/sigil-rifle/s3.json) / [kit](/reference/sigil-rifle/kit.md)

| source seconds | source frame | game tick | range |
|---:|---:|---:|---|
| 23.083333 | 554 | 63 | ranged_projectile |
| 24.708333 | 593 | 96 | aoe_at_target |

1 tick = 50 ms。量子化は最近傍、半tickは切り上げ。原資料記載の接触遮蔽・コイン対応の不確実性は保持。旧intentは履歴資料であり、新しい再現結果ではない。

## round 2 実装と検証

24fpsの参照を保持。S1は銃を使った近接、S2は実投射体2発、S3は発砲と標的側の紋章へ訂正。

比較用接触tick: [63, 96]。源のコイン対応にある不確実性は保持する。

状態: 現版撮影あり。膝立ちの構え、銃口の花状フラッシュ、紋章内側の文字・同心円・角度、ズームが不足。射線は棒状で原動画の残光と異なる。

- 内容SHA-256: `696a64c879ef13a6857a33c1a89530ee8de1408abd8e5b6b82986fde092793dc`
- 参照SHA-256: `46757b46b53cfca81feda27cf5c41e6a3c074315e6ee6e465f68f31079352dc1`
- 修正反復: 1 / 最大4（同版の撮影停止による再撮影は別記）
- 撮影: `build/vfx-shot/sigil-rifle-s3/compare/`、標的HP: 172.448f
- [共通起点の比較画像](/evidence/sigil-rifle-s3.png)
- [全操作の記録](/report/sigil-rifle)

強制発動の撮影は資源・クールダウン・入力の証明ではない。HP減少は10%・2倍のランダムクリティカルの影響を受け、実ヒット数と読み替えない。
