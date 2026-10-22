# Spider Blade Ryoshu / S1

状態: astra2解析済・再制作中

原資料: [フレーム解析](/reference/gossamer-katana/s1.json) / [kit](/reference/gossamer-katana/kit.md)

| source seconds | source frame | game tick | range |
|---:|---:|---:|---|
| 12.445767 | 373 | 12 | dash_melee |
| 13.046367 | 391 | 24 | dash_melee |
| 13.780433 | 413 | 39 | dash_melee |

1 tick = 50 ms。量子化は最近傍、半tickは切り上げ。原資料記載の接触遮蔽・コイン対応の不確実性は保持。旧intentは履歴資料であり、新しい再現結果ではない。

## round 2 実装と検証

通過斬り、遠方の分身攻撃、標的の交差線を分離。S3は観測された1接触。停止したS1撮影は破棄して同版を撮り直した。

比較用接触tick: [12, 24, 39]。源のコイン対応にある不確実性は保持する。

状態: 現版撮影あり。分身は完全な人物シルエットではない。反転時の身体の向き、刀の曲率、薄い青い弧と画面を横切る残光が未一致。

- 内容SHA-256: `3f63ff4b81c76bbe6585962a66b4e725b8686a07a5b770906dd3a553a35f8841`
- 参照SHA-256: `14059456bd6239c9b35010426f3043cba87bd01c594d65a0da87e6c354baca1d`
- 修正反復: 2 / 最大4（同版の撮影停止による再撮影は別記）
- 撮影: `build/vfx-shot/gossamer-katana-s1/compare/`、標的HP: 177.36801f
- [共通起点の比較画像](/evidence/gossamer-katana-s1.png)
- [全操作の記録](/report/gossamer-katana)

強制発動の撮影は資源・クールダウン・入力の証明ではない。HP減少は10%・2倍のランダムクリティカルの影響を受け、実ヒット数と読み替えない。
