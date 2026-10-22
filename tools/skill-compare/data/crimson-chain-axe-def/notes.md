# Haute Couture::The Crimson God of Le Rouge Sinclair / DEF

状態: astra2解析済・再制作中

原資料: [フレーム解析](/reference/crimson-chain-axe/def.json) / [kit](/reference/crimson-chain-axe/kit.md)

| source seconds | source frame | game tick | range |
|---:|---:|---:|---|

1 tick = 50 ms。量子化は最近傍、半tickは切り上げ。原資料記載の接触遮蔽・コイン対応の不確実性は保持。旧intentは履歴資料であり、新しい再現結果ではない。

## round 2 実装と検証

通常形態を素手からの赤い針へ変更。S3は表示専用の追加投射体で連射を描き、ダメージ付き投射体は3発に限定。

比較用接触tick: 原動画の攻撃接触なし。源のコイン対応にある不確実性は保持する。

状態: 現版撮影あり。手を上げる姿勢と大きなカメラ寄りが不足。針の色・密度・光の面積は未一致。Q後に攻撃セットを持続的に変身版へ切り替える処理は未実装。

- 内容SHA-256: `b473b6e9275f7a1419e24cfaa03a2a54c82b1cb90ba93c0ed03db8ec4ed4ef9e`
- 参照SHA-256: `11fa0f66373f5863d1056cb10e50d626cd5d1749d56250176dcc9285a4cf9046`
- 修正反復: 2 / 最大4（同版の撮影停止による再撮影は別記）
- 撮影: `build/vfx-shot/crimson-chain-axe-def/compare/`、標的HP: 200.0f
- [共通起点の比較画像](/evidence/crimson-chain-axe-def.png)
- [全操作の記録](/report/crimson-chain-axe)

強制発動の撮影は資源・クールダウン・入力の証明ではない。HP減少は10%・2倍のランダムクリティカルの影響を受け、実ヒット数と読み替えない。
