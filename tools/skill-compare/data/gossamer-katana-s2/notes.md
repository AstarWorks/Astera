# Spider Blade Ryoshu / S2

状態: astra2解析済・再制作中

原資料: [フレーム解析](/reference/gossamer-katana/s2.json) / [kit](/reference/gossamer-katana/kit.md)

| source seconds | source frame | game tick | range |
|---:|---:|---:|---|
| 15.982633 | 479 | 7 | ranged_projectile |
| 17.150467 | 514 | 30 | dash_melee |
| 18.6186 | 558 | 59 | aoe_at_target |

1 tick = 50 ms。量子化は最近傍、半tickは切り上げ。原資料記載の接触遮蔽・コイン対応の不確実性は保持。旧intentは履歴資料であり、新しい再現結果ではない。

## round 2 実装と検証

通過斬り、遠方の分身攻撃、標的の交差線を分離。S3は観測された1接触。停止したS1撮影は破棄して同版を撮り直した。

比較用接触tick: [7, 30, 59]。源のコイン対応にある不確実性は保持する。

状態: 現版撮影あり。分身は完全な人物シルエットではない。反転時の身体の向き、刀の曲率、薄い青い弧と画面を横切る残光が未一致。

- 内容SHA-256: `9e38c12a31790aa10f74ba1a8522618619bbc0c084a5c65e9472ecab705129c4`
- 参照SHA-256: `2858125222e9397c7544911fe365760c44b7f399ab22f6a5a4d9894d8093fc83`
- 修正反復: 2 / 最大4（同版の撮影停止による再撮影は別記）
- 撮影: `build/vfx-shot/gossamer-katana-s2/compare/`、標的HP: 176.38399f
- [共通起点の比較画像](/evidence/gossamer-katana-s2.png)
- [全操作の記録](/report/gossamer-katana)

強制発動の撮影は資源・クールダウン・入力の証明ではない。HP減少は10%・2倍のランダムクリティカルの影響を受け、実ヒット数と読み替えない。
