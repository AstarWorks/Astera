# Zwei West Section 3 Ishmael / bastion-greatsword

2026-10-23 round 2。**再現合格ではない。** 撮影と一致判定を分けて記録する。

S1/S2/S3の接触時刻を再解析に合わせ、薙ぎの幅・薄さを修正。DEFは観測された防御区間に合わせた。

残差: 身体の低い踏み込み・両手の上段姿勢・原動画の極端な寄りを再現できていない。斬撃と柱の輪郭、光量にも差がある。

| 操作 | 実装 | 原動画 | 実機版 | 反復 | 標的HP（200から） |
|---|---|---|---|---:|---|
| [S1](/case.html?id=bastion-greatsword-s1) | `bastion-greatsword-s1` | 原動画あり | 現版撮影あり | 2 | 184.256f |
| [S2](/case.html?id=bastion-greatsword-s2) | `bastion-greatsword-s2` | 原動画あり | 現版撮影あり | 2 | 175.4f |
| [S3](/case.html?id=zwei-s3) | (削除済み) | 原動画あり | なし (全削除) | 0 | - |
| [DEF](/case.html?id=bastion-greatsword-def) | `bastion-greatsword-def` | 原動画あり | 現版撮影あり | 1 | 200.0f |
| [EGO](/case.html?id=bastion-greatsword-ego) | `bastion-greatsword-ego` | wiki由来 | 現版撮影あり | 1 | 172.448f |

## 通常操作の記録

fixtureで資源を準備した後、実際のキー・マウス入力で撮影。標的HPは200からの値。HP変化なしは防御・変身の不発を意味しない。個別の発光・移動は比較画面の「通常操作で発動」で確認する。

| 操作 | 実入力 | 標的HP | 記録 |
|---|---|---|---|
| S1 | `left_click` | 184.256f | 現版の実入力撮影あり |
| S2 | `right_click` | 159.656f | 現版の実入力撮影あり |
| S3 | `sneak_right` | 183.27202f | 現版の実入力撮影あり |
| DEF | `sneak_left` | 200.0f | 現版の実入力撮影あり |
| EGO | `drop` | 172.448f | 現版の実入力撮影あり |

## 検証範囲と未解決事項

- 比較用撮影: 実武器を装備した実クライアント、NoAI標的、20fps、単一起点。caseとmetaの参照・内容ハッシュが一致する撮影だけを配信。
- 対象HPは補助値。ランダムクリティカルがあるため、HP合計から命中回数の確定はしていない。
- 初回の撮影ではShift押下とクリックの間隔不足、およびE.G.O補充用の狭いconeが標的を外す不備を確認した。該当映像は除外し、Shiftを0.3秒保持、補充命中を確認して同じ内容版を撮り直した。Couture Qではゲージ不足時の拒否表示も確認。全武器の全資源不足確認とは別。
- 実入力映像は実機欄の「通常操作で発動」から選ぶ。撮影用fixtureでレベル・資源を準備しており、資源不足時の拒否確認とは別。
- 同時Display予算は単体定義だけでなく重複・trail・子skillを含む実測が未完了。数値が未測定の項目は合格扱いしない。
- 術者・対戦相手の視界、移動標的への追撃、全資源不足・クールダウン拒否の受け入れ確認は未完了。
- 人物の両手・膝・胴体のポーズ、反撃の条件分岐、持続的な変身セットは、今回のcontent制作で未実装。必要なruntime作業は別途特定し、今回Kotlinを拡張して解決したとは扱わない。

[制作手順](../../../../docs/contributing/limbus-skill-reproduction.md) / [参照解析](/reference/bastion-greatsword/notes.md)
