# Spider Blade Ryoshu / gossamer-katana

2026-10-23 round 2。**再現合格ではない。** 撮影と一致判定を分けて記録する。

通過斬り、遠方の分身攻撃、標的の交差線を分離。S3は観測された1接触。停止したS1撮影は破棄して同版を撮り直した。

残差: 分身は完全な人物シルエットではない。反転時の身体の向き、刀の曲率、薄い青い弧と画面を横切る残光が未一致。

| 操作 | 実装 | 原動画 | 実機版 | 反復 | 標的HP（200から） |
|---|---|---|---|---:|---|
| [S1](/case.html?id=gossamer-katana-s1) | `gossamer-katana-s1` | 原動画あり | 現版撮影あり | 2 | 177.36801f |
| [S2](/case.html?id=gossamer-katana-s2) | `gossamer-katana-s2` | 原動画あり | 現版撮影あり | 2 | 176.38399f |
| [S3](/case.html?id=gossamer-katana-s3) | `gossamer-katana-s3` | 原動画あり | 現版撮影あり | 1 | 180.32f |
| [DEF](/case.html?id=gossamer-katana-def) | `gossamer-katana-def` | 原動画あり | 現版撮影あり | 1 | 200.0f |
| [EGO](/case.html?id=gossamer-katana-ego) | `gossamer-katana-ego` | wiki由来 | 現版撮影あり | 2 | 176.384f |

## 通常操作の記録

fixtureで資源を準備した後、実際のキー・マウス入力で撮影。標的HPは200からの値。HP変化なしは防御・変身の不発を意味しない。個別の発光・移動は比較画面の「通常操作で発動」で確認する。

| 操作 | 実入力 | 標的HP | 記録 |
|---|---|---|---|
| S1 | `left_click` | 177.36801f | 現版の実入力撮影あり |
| S2 | `right_click` | 176.38399f | 現版の実入力撮影あり |
| S3 | `sneak_right` | 180.32f | 現版の実入力撮影あり |
| DEF | `sneak_left` | 200.0f | 現版の実入力撮影あり |
| EGO | `drop` | 152.768f | 現版の実入力撮影あり |

## 検証範囲と未解決事項

- 比較用撮影: 実武器を装備した実クライアント、NoAI標的、20fps、単一起点。caseとmetaの参照・内容ハッシュが一致する撮影だけを配信。
- 対象HPは補助値。ランダムクリティカルがあるため、HP合計から命中回数の確定はしていない。
- 初回の撮影ではShift押下とクリックの間隔不足、およびE.G.O補充用の狭いconeが標的を外す不備を確認した。該当映像は除外し、Shiftを0.3秒保持、補充命中を確認して同じ内容版を撮り直した。Couture Qではゲージ不足時の拒否表示も確認。全武器の全資源不足確認とは別。
- 実入力映像は実機欄の「通常操作で発動」から選ぶ。撮影用fixtureでレベル・資源を準備しており、資源不足時の拒否確認とは別。
- 同時Display予算は単体定義だけでなく重複・trail・子skillを含む実測が未完了。数値が未測定の項目は合格扱いしない。
- 術者・対戦相手の視界、移動標的への追撃、全資源不足・クールダウン拒否の受け入れ確認は未完了。
- 人物の両手・膝・胴体のポーズ、反撃の条件分岐、持続的な変身セットは、今回のcontent制作で未実装。必要なruntime作業は別途特定し、今回Kotlinを拡張して解決したとは扱わない。

[制作手順](../../../../docs/contributing/limbus-skill-reproduction.md) / [参照解析](/reference/gossamer-katana/notes.md)

### round79：追加観測7区間を比較画面へ対応付け

未対応だった追加区間を独立した参照枠に追加。7つの新技と断定せず、正式キット・状態分岐との対応は未確定として扱う。計940フレーム。専用実装・実機撮影は未完了で、既存技の撮影を流用しない。

| 参照枠 | フレーム数 | 状態 |
|---|---:|---|
| [gossamer-katana-arc-chain](/case.html?id=gossamer-katana-arc-chain) | 80 | 未実装・未接続 |
| [gossamer-katana-echoshear-candidate](/case.html?id=gossamer-katana-echoshear-candidate) | 142 | 未実装・未接続 |
| [gossamer-katana-termination-candidate](/case.html?id=gossamer-katana-termination-candidate) | 22 | 未実装・未接続 |
| [gossamer-katana-crescent-fan](/case.html?id=gossamer-katana-crescent-fan) | 67 | 未実装・未接続 |
| [gossamer-katana-dash-flurry](/case.html?id=gossamer-katana-dash-flurry) | 82 | 未実装・未接続 |
| [gossamer-katana-string-connection-candidate](/case.html?id=gossamer-katana-string-connection-candidate) | 332 | 未実装・未接続 |
| [gossamer-katana-spectral-montage](/case.html?id=gossamer-katana-spectral-montage) | 215 | 未実装・未接続 |

最後の連続攻撃は本体の移動と分身体の移動を分離する。短い対象上の青い発光は、追加ダメージと断定しない。今回の観察範囲と残課題は `runtime-review/gossamer-coverage-round79.json` に記録。

### round80：4接触の連続斬撃・直接呼出し試作

接触前後の原動画フレームを再観察し、本体が毎接触ごとに左右交互へ移動するという旧解釈を訂正。最初の2接触は同じ側、3接触目の前に反対側へ通過する。重なる残像と本体を区別する。

専用 `gossamer-arc-chain` を作成し、原動画f645/659/672/683に対応する約7/17/25/33tickに試作の接触を配置した。最初の斬撃が移動前の位置に残る初回映像を差し替え、実機73フレーム、遅延最大3ms・重複最大1、表示数観測最大30を確認。HP [200.0, 194.096, 188.19199, 182.28798, 176.38397]。試作ダメージ量・回数は原作キットの認定ではない。

同じ側から2接触、通過後に反対側で2接触と低い回復姿勢の試作を実機確認。正式キット・コイン対応・ダメージ回数は未確定。身体の向き、箱状残像、刀の曲率、途切れる細い縁、白い帯状残光、花模様、カメラは未一致。 通常入力・状態分岐には未接続。詳細: `runtime-review/gossamer-arc-chain-round80.json`。

### round81：通過後の身体の向きと刀の重複

既存の距離0・face_movement付きdashを25tickに指定し、その場で180度向き直る。後半rigの補正180度を外し、身体と刀の向きを合わせた。初回再撮影で固体の刀が2本出る表示期間の重なりを発見し、構え0–6、横斬り7–13、斬り上げ14–22、到着25–29、返し30–38、回復39–53tickに分けて再撮影。

実機の向きは−90→90度、前後の座標は共に[307.5,100,300.5]。73フレーム、遅延最大2.0ms、重複最大1、表示数観測最大30。HP [200.0, 194.096, 188.19199, 182.28798, 176.38397]。

通過後に位置を変えず敵側へ向き直ることを実機映像と座標・角度記録で確認。刀の表示期間の重なりを解消。箱状残像、刀の曲率、途切れる細い縁、白い帯状残光、花模様、腕姿勢、カメラは未一致。通常入力・状態分岐・正式キット対応は未確定。 これは観測した通過に合わせた固定反転であり、自由に移動する対象へ常時向く機能ではない。詳細: `runtime-review/gossamer-facing-round81.json`。

### round82：残像の表示と未一致の輪郭

専用 arc-chain の箱状残像を青2回・赤1回の平面人型へ変更。裏面を負のXスケールで反転した初版では青い残像が消えたため、反転した画像素材と正のスケールへ修正して再撮影した。実機フレーム16/34で青、43で赤の表示を確認。原動画f683の髪・衣服・脚・刀の輪郭とは大きな差があり、単一姿勢の簡略人型を完成扱いしない。

73フレーム、カメラ遅延最大3ms、重複最大1。表示数観測最大25、HPは4段階減少。青・赤の平面人型残像は実機で表示確認。髪・衣服・姿勢・刀の曲率、途切れる細い斬撃縁、白い帯状残光、花模様、腕姿勢、カメラは未一致。通常入力・状態分岐・正式キット対応は未確定。 詳細: `runtime-review/gossamer-afterimage-round82.json`。

両面spriteは画像反転とポリゴンの面向きを別に確認する。負スケールで裏面表示を代用せず、前後から実クライアントで確認する。撮影品質の合格と形状一致の合格は分ける。
