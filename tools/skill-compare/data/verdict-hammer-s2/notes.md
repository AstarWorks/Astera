# Haute Couture::The Black Knight of Le Noir Don Quixote / S2

状態: astra2解析済・再制作中

原資料: [フレーム解析](/reference/verdict-hammer/s2.json) / [kit](/reference/verdict-hammer/kit.md)

| source seconds | source frame | game tick | range |
|---:|---:|---:|---|
| 36.603233 | 1097 | 19 | ranged_projectile |

1 tick = 50 ms。量子化は最近傍、半tickは切り上げ。原資料記載の接触遮蔽・コイン対応の不確実性は保持。旧intentは履歴資料であり、新しい再現結果ではない。

## round 2 実装と検証

召喚物を剣ではなく釘の軸・頭に変更。S3は釘、突進、ハンマーの順序と接触時刻を訂正。

比較用接触tick: [19]。源のコイン対応にある不確実性は保持する。

状態: 現版撮影あり。釘のV字の見え方、ハンマーの両手保持、飛び上がり・振り下ろし姿勢、輪の面積と寄りが未一致。DEFの3接触とwiki上2コインの対応は未確定。

- 内容SHA-256: `6e47d306bbf5806d90c71a605069e03c9d5cd889c0fbacd8b11e6e60b9528b38`
- 参照SHA-256: `78941c376bbbab777f9e8ac3d93667f8211f0ad1a693e3ff9bd861bb014dc012`
- 修正反復: 1 / 最大4（同版の撮影停止による再撮影は別記）
- 撮影: `build/vfx-shot/verdict-hammer-s2/compare/`、標的HP: 188.192f
- [共通起点の比較画像](/evidence/verdict-hammer-s2.png)
- [全操作の記録](/report/verdict-hammer)

強制発動の撮影は資源・クールダウン・入力の証明ではない。HP減少は10%・2倍のランダムクリティカルの影響を受け、実ヒット数と読み替えない。
