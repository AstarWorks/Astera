# Spider Blade Ryoshu / EGO

状態: wiki由来・原動画発動未確認

原資料: [フレーム解析](/reference/gossamer-katana/ego.json) / [kit](/reference/gossamer-katana/kit.md)

| source seconds | source frame | game tick | range |
|---:|---:|---:|---|

1 tick = 50 ms。量子化は最近傍、半tickは切り上げ。原資料記載の接触遮蔽・コイン対応の不確実性は保持。旧intentは履歴資料であり、新しい再現結果ではない。

## round 2 実装と検証

通過斬り、遠方の分身攻撃、標的の交差線を分離。S3は観測された1接触。停止したS1撮影は破棄して同版を撮り直した。

比較用接触tick: 原動画の攻撃接触なし。源のコイン対応にある不確実性は保持する。

状態: 現版撮影あり。原動画でこの操作は未観測。wiki由来のAstera向け設計であり、原作再現の合格対象に含めない。

- 内容SHA-256: `2e735f9f3c68042768d13b192b847cf3af3cc4c9fa4c7a6a157d1d8efee93bec`
- 参照SHA-256: `b3ab19bb18fe8bb6fb3ac55f20ebecb0e64ed12cdb6c0e0a14d3de5617e57ce7`
- 修正反復: 2 / 最大4（同版の撮影停止による再撮影は別記）
- 撮影: `build/vfx-shot/gossamer-katana-ego/compare/`、標的HP: 176.384f
- [共通起点の比較画像](/evidence/gossamer-katana-ego.png)
- [全操作の記録](/report/gossamer-katana)

強制発動の撮影は資源・クールダウン・入力の証明ではない。HP減少は10%・2倍のランダムクリティカルの影響を受け、実ヒット数と読み替えない。
