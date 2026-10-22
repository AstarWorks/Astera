# Haute Couture::The Crimson God of Le Rouge Sinclair / S3

状態: astra2解析済・再制作中

原資料: [フレーム解析](/reference/crimson-chain-axe/s3.json) / [kit](/reference/crimson-chain-axe/kit.md)

| source seconds | source frame | game tick | range |
|---:|---:|---:|---|
| 51.818433 | 1553 | 58 | ranged_projectile |
| 52.485767 | 1573 | 71 | ranged_projectile |
| 53.3533 | 1599 | 89 | ranged_projectile |

1 tick = 50 ms。量子化は最近傍、半tickは切り上げ。原資料記載の接触遮蔽・コイン対応の不確実性は保持。旧intentは履歴資料であり、新しい再現結果ではない。

## round 2 実装と検証

通常形態を素手からの赤い針へ変更。S3は表示専用の追加投射体で連射を描き、ダメージ付き投射体は3発に限定。

比較用接触tick: [58, 71, 89]。源のコイン対応にある不確実性は保持する。

状態: 現版撮影あり。手を上げる姿勢と大きなカメラ寄りが不足。針の色・密度・光の面積は未一致。Q後に攻撃セットを持続的に変身版へ切り替える処理は未実装。

- 内容SHA-256: `49c35c2f3fe8946171db78bd5a8e7f5065aa4cea04bf86045d1ffe4dd3bec9e6`
- 参照SHA-256: `d9c1cfc878b196dc1aa64dac977cb3c045da9a5df7e130dcb22f8fac0d5209de`
- 修正反復: 3 / 最大4（同版の撮影停止による再撮影は別記）
- 撮影: `build/vfx-shot/crimson-chain-axe-s3/compare/`、標的HP: 179.336f
- [共通起点の比較画像](/evidence/crimson-chain-axe-s3.png)
- [全操作の記録](/report/crimson-chain-axe)

強制発動の撮影は資源・クールダウン・入力の証明ではない。HP減少は10%・2倍のランダムクリティカルの影響を受け、実ヒット数と読み替えない。
