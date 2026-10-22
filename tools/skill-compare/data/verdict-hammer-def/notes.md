# Haute Couture::The Black Knight of Le Noir Don Quixote / DEF

状態: astra2解析済・再制作中

原資料: [フレーム解析](/reference/verdict-hammer/def.json) / [kit](/reference/verdict-hammer/kit.md)

| source seconds | source frame | game tick | range |
|---:|---:|---:|---|
| 48.782067 | 1462 | 97 | ranged_projectile |
| 49.8498 | 1494 | 118 | dash_melee |
| 50.283567 | 1507 | 127 | dash_melee |

1 tick = 50 ms。量子化は最近傍、半tickは切り上げ。原資料記載の接触遮蔽・コイン対応の不確実性は保持。旧intentは履歴資料であり、新しい再現結果ではない。

## round 2 実装と検証

召喚物を剣ではなく釘の軸・頭に変更。S3は釘、突進、ハンマーの順序と接触時刻を訂正。

比較用接触tick: [97, 118, 127]。源のコイン対応にある不確実性は保持する。

状態: 現版撮影あり。釘のV字の見え方、ハンマーの両手保持、飛び上がり・振り下ろし姿勢、輪の面積と寄りが未一致。DEFの3接触とwiki上2コインの対応は未確定。

- 内容SHA-256: `fc96e8468fd2320a0f6c1bfa0006243ea2ce654157ffae16f751e2366ccbefd6`
- 参照SHA-256: `43eaa22895c832375f083feac599e678276e2ad8b162553a334b4bc9d34585ca`
- 修正反復: 1 / 最大4（同版の撮影停止による再撮影は別記）
- 撮影: `build/vfx-shot/verdict-hammer-def/compare/`、標的HP: 178.35199f
- [共通起点の比較画像](/evidence/verdict-hammer-def.png)
- [全操作の記録](/report/verdict-hammer)

強制発動の撮影は資源・クールダウン・入力の証明ではない。HP減少は10%・2倍のランダムクリティカルの影響を受け、実ヒット数と読み替えない。
