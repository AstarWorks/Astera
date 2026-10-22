# Haute Couture::The Black Knight of Le Noir Don Quixote / verdict-hammer

2026-10-23 round 2。**再現合格ではない。** 撮影と一致判定を分けて記録する。

召喚物を剣ではなく釘の軸・頭に変更。S3は釘、突進、ハンマーの順序と接触時刻を訂正。

残差: 釘のV字の見え方、ハンマーの両手保持、飛び上がり・振り下ろし姿勢、輪の面積と寄りが未一致。DEFの3接触とwiki上2コインの対応は未確定。

| 操作 | 実装 | 原動画 | 実機版 | 反復 | 標的HP（200から） |
|---|---|---|---|---:|---|
| [S1](/case.html?id=verdict-hammer-s1) | `verdict-hammer-s1` | 原動画あり | 現版撮影あり | 1 | 183.272f |
| [S2](/case.html?id=verdict-hammer-s2) | `verdict-hammer-s2` | 原動画あり | 現版撮影あり | 1 | 188.192f |
| [S3](/case.html?id=verdict-hammer-s3) | `verdict-hammer-s3` | 原動画あり | 現版撮影あり | 1 | 166.544f |
| [DEF](/case.html?id=verdict-hammer-def) | `verdict-hammer-def` | 原動画あり | 現版撮影あり | 1 | 178.35199f |
| [EGO](/case.html?id=verdict-hammer-ego) | `verdict-hammer-ego` | wiki由来 | 現版撮影あり | 1 | 152.768f |

## 通常操作の記録

fixtureで資源を準備した後、実際のキー・マウス入力で撮影。標的HPは200からの値。HP変化なしは防御・変身の不発を意味しない。個別の発光・移動は比較画面の「通常操作で発動」で確認する。

| 操作 | 実入力 | 標的HP | 記録 |
|---|---|---|---|
| S1 | `left_click` | 176.384f | 現版の実入力撮影あり |
| S2 | `right_click` | 188.192f | 現版の実入力撮影あり |
| S3 | `sneak_right` | 166.544f | 現版の実入力撮影あり |
| DEF | `sneak_left` | 171.46399f | 現版の実入力撮影あり |
| EGO | `drop` | 152.768f | 現版の実入力撮影あり |

## 検証範囲と未解決事項

- 比較用撮影: 実武器を装備した実クライアント、NoAI標的、20fps、単一起点。caseとmetaの参照・内容ハッシュが一致する撮影だけを配信。
- 対象HPは補助値。ランダムクリティカルがあるため、HP合計から命中回数の確定はしていない。
- 初回の撮影ではShift押下とクリックの間隔不足、およびE.G.O補充用の狭いconeが標的を外す不備を確認した。該当映像は除外し、Shiftを0.3秒保持、補充命中を確認して同じ内容版を撮り直した。Couture Qではゲージ不足時の拒否表示も確認。全武器の全資源不足確認とは別。
- 実入力映像は実機欄の「通常操作で発動」から選ぶ。撮影用fixtureでレベル・資源を準備しており、資源不足時の拒否確認とは別。
- 同時Display予算は単体定義だけでなく重複・trail・子skillを含む実測が未完了。数値が未測定の項目は合格扱いしない。
- 術者・対戦相手の視界、移動標的への追撃、全資源不足・クールダウン拒否の受け入れ確認は未完了。
- 人物の両手・膝・胴体のポーズ、反撃の条件分岐、持続的な変身セットは、今回のcontent制作で未実装。必要なruntime作業は別途特定し、今回Kotlinを拡張して解決したとは扱わない。

[制作手順](../../../../docs/contributing/limbus-skill-reproduction.md) / [参照解析](/reference/verdict-hammer/notes.md)
