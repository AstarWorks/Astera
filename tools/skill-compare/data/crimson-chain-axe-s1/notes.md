# Haute Couture::The Crimson God of Le Rouge Sinclair / S1

状態: astra2解析済・再制作中

原資料: [フレーム解析](/reference/crimson-chain-axe/s1.json) / [kit](/reference/crimson-chain-axe/kit.md)

| source seconds | source frame | game tick | range |
|---:|---:|---:|---|
| 12.479133 | 374 | 27 | ranged_projectile |

1 tick = 50 ms。量子化は最近傍、半tickは切り上げ。原資料記載の接触遮蔽・コイン対応の不確実性は保持。旧intentは履歴資料であり、新しい再現結果ではない。

## round 2 実装と検証

通常形態を素手からの赤い針へ変更。S3は表示専用の追加投射体で連射を描き、ダメージ付き投射体は3発に限定。

比較用接触tick: [27]。源のコイン対応にある不確実性は保持する。

状態: 現版撮影あり。手を上げる姿勢と大きなカメラ寄りが不足。針の色・密度・光の面積は未一致。Q後に攻撃セットを持続的に変身版へ切り替える処理は未実装。

- 内容SHA-256: `3106740f067e7d521775b4e690227e92f4294317cac9ec559ed8728778a6f01b`
- 参照SHA-256: `51dd80c5d7bff30ba3bb05e43420a985c0c54b30ec526f1620bdf302cc921334`
- 修正反復: 3 / 最大4（同版の撮影停止による再撮影は別記）
- 撮影: `build/vfx-shot/crimson-chain-axe-s1/compare/`、標的HP: 193.112f
- [共通起点の比較画像](/evidence/crimson-chain-axe-s1.png)
- [全操作の記録](/report/crimson-chain-axe)

強制発動の撮影は資源・クールダウン・入力の証明ではない。HP減少は10%・2倍のランダムクリティカルの影響を受け、実ヒット数と読み替えない。
