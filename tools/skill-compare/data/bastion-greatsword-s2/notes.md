# Zwei West Section 3 Ishmael / S2

状態: astra2解析済・再制作中

原資料: [フレーム解析](/reference/bastion-greatsword/s2.json) / [kit](/reference/bastion-greatsword/kit.md)

| source seconds | source frame | game tick | range |
|---:|---:|---:|---|
| 15.3 | 459 | 13 | melee |
| 16.233 | 487 | 32 | melee |
| 17.3 | 519 | 53 | melee |

1 tick = 50 ms。量子化は最近傍、半tickは切り上げ。原資料記載の接触遮蔽・コイン対応の不確実性は保持。旧intentは履歴資料であり、新しい再現結果ではない。

## round 2 実装と検証

S1/S2/S3の接触時刻を再解析に合わせ、薙ぎの幅・薄さを修正。DEFは観測された防御区間に合わせた。

比較用接触tick: [13, 32, 53]。源のコイン対応にある不確実性は保持する。

状態: 現版撮影あり。身体の低い踏み込み・両手の上段姿勢・原動画の極端な寄りを再現できていない。斬撃と柱の輪郭、光量にも差がある。

- 内容SHA-256: `7a2e15f98cb18c36e752074962e4ee45b9c92627b64774552bfc54411d79f870`
- 参照SHA-256: `c4f92d2c5f3d2b5058d4932f0f8d2127d55cf21d0e135d850783b7c783f01a25`
- 修正反復: 2 / 最大4（同版の撮影停止による再撮影は別記）
- 撮影: `build/vfx-shot/bastion-greatsword-s2/compare/`、標的HP: 175.4f
- [共通起点の比較画像](/evidence/bastion-greatsword-s2.png)
- [全操作の記録](/report/bastion-greatsword)

強制発動の撮影は資源・クールダウン・入力の証明ではない。HP減少は10%・2倍のランダムクリティカルの影響を受け、実ヒット数と読み替えない。
