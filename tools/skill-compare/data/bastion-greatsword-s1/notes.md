# Zwei West Section 3 Ishmael / S1

状態: astra2解析済・再制作中

原資料: [フレーム解析](/reference/bastion-greatsword/s1.json) / [kit](/reference/bastion-greatsword/kit.md)

| source seconds | source frame | game tick | range |
|---:|---:|---:|---|
| 11.967 | 359 | 7 | melee |
| 13.2 | 396 | 31 | melee |

1 tick = 50 ms。量子化は最近傍、半tickは切り上げ。原資料記載の接触遮蔽・コイン対応の不確実性は保持。旧intentは履歴資料であり、新しい再現結果ではない。

## round 2 実装と検証

S1/S2/S3の接触時刻を再解析に合わせ、薙ぎの幅・薄さを修正。DEFは観測された防御区間に合わせた。

比較用接触tick: [7, 31]。源のコイン対応にある不確実性は保持する。

状態: 現版撮影あり。身体の低い踏み込み・両手の上段姿勢・原動画の極端な寄りを再現できていない。斬撃と柱の輪郭、光量にも差がある。

- 内容SHA-256: `065058aa6fee8b39a0d31d59aca98e4012cf8023a457b8aef074866deb8f20c6`
- 参照SHA-256: `9a9b8b2062d3b702777cdc0b7abe0fe9c2c5987e676d581d992ca3640125bb5e`
- 修正反復: 2 / 最大4（同版の撮影停止による再撮影は別記）
- 撮影: `build/vfx-shot/bastion-greatsword-s1/compare/`、標的HP: 184.256f
- [共通起点の比較画像](/evidence/bastion-greatsword-s1.png)
- [全操作の記録](/report/bastion-greatsword)

強制発動の撮影は資源・クールダウン・入力の証明ではない。HP減少は10%・2倍のランダムクリティカルの影響を受け、実ヒット数と読み替えない。
