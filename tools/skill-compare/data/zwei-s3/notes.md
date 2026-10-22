# Zwei West Section 3 Ishmael / S3

状態: astra2解析済・再制作中

原資料: [フレーム解析](/reference/bastion-greatsword/s3.json) / [kit](/reference/bastion-greatsword/kit.md)

| source seconds | source frame | game tick | range |
|---:|---:|---:|---|
| 19.4 | 582 | 10 | melee |
| 20.9 | 627 | 40 | dash_melee |
| 22.033 | 661 | 63 | melee |

1 tick = 50 ms。量子化は最近傍、半tickは切り上げ。原資料記載の接触遮蔽・コイン対応の不確実性は保持。旧intentは履歴資料であり、新しい再現結果ではない。

## round 2 実装と検証

S1/S2/S3の接触時刻を再解析に合わせ、薙ぎの幅・薄さを修正。DEFは観測された防御区間に合わせた。

比較用接触tick: [10, 40, 63]。源のコイン対応にある不確実性は保持する。

状態: 現版撮影あり。身体の低い踏み込み・両手の上段姿勢・原動画の極端な寄りを再現できていない。斬撃と柱の輪郭、光量にも差がある。

- 内容SHA-256: `218148194e324d068bd640c3c4c36071387992b28958ec25de56f1dca6584feb`
- 参照SHA-256: `42654faba56df907a2558a4370210026dd6d5e3ac8f95a95d411843f87ebf466`
- 修正反復: 2 / 最大4（同版の撮影停止による再撮影は別記）
- 撮影: `build/vfx-shot/bastion-greatsword-s3/compare/`、標的HP: 183.27202f
- [共通起点の比較画像](/evidence/zwei-s3.png)
- [全操作の記録](/report/bastion-greatsword)

強制発動の撮影は資源・クールダウン・入力の証明ではない。HP減少は10%・2倍のランダムクリティカルの影響を受け、実ヒット数と読み替えない。
