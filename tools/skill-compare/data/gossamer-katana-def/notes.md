# Spider Blade Ryoshu / DEF

状態: astra2解析済・再制作中

原資料: [フレーム解析](/reference/gossamer-katana/def.json) / [kit](/reference/gossamer-katana/kit.md)

| source seconds | source frame | game tick | range |
|---:|---:|---:|---|

1 tick = 50 ms。量子化は最近傍、半tickは切り上げ。原資料記載の接触遮蔽・コイン対応の不確実性は保持。旧intentは履歴資料であり、新しい再現結果ではない。

## round 2 実装と検証

通過斬り、遠方の分身攻撃、標的の交差線を分離。S3は観測された1接触。停止したS1撮影は破棄して同版を撮り直した。

比較用接触tick: 原動画の攻撃接触なし。源のコイン対応にある不確実性は保持する。

状態: 現版撮影あり。分身は完全な人物シルエットではない。反転時の身体の向き、刀の曲率、薄い青い弧と画面を横切る残光が未一致。

- 内容SHA-256: `e980e4d8ffdd222fa557086d6a95a9449b456fe73492017faecd82817ea6dadd`
- 参照SHA-256: `d2ed1a74c171bb8621ef4ef5e342b350c6179a966573604b2b7d9138d0781251`
- 修正反復: 1 / 最大4（同版の撮影停止による再撮影は別記）
- 撮影: `build/vfx-shot/gossamer-katana-def/compare/`、標的HP: 200.0f
- [共通起点の比較画像](/evidence/gossamer-katana-def.png)
- [全操作の記録](/report/gossamer-katana)

強制発動の撮影は資源・クールダウン・入力の証明ではない。HP減少は10%・2倍のランダムクリティカルの影響を受け、実ヒット数と読み替えない。
