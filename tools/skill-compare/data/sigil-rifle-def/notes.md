# Fell Bullet Yi Sang / DEF

状態: wiki由来・原動画発動未確認

原資料: [フレーム解析](/reference/sigil-rifle/def.json) / [kit](/reference/sigil-rifle/kit.md)

| source seconds | source frame | game tick | range |
|---:|---:|---:|---|

1 tick = 50 ms。量子化は最近傍、半tickは切り上げ。原資料記載の接触遮蔽・コイン対応の不確実性は保持。旧intentは履歴資料であり、新しい再現結果ではない。

## round 2 実装と検証

24fpsの参照を保持。S1は銃を使った近接、S2は実投射体2発、S3は発砲と標的側の紋章へ訂正。

比較用接触tick: 原動画の攻撃接触なし。源のコイン対応にある不確実性は保持する。

状態: 現版撮影あり。原動画でこの操作は未観測。wiki由来のAstera向け設計であり、原作再現の合格対象に含めない。

- 内容SHA-256: `4acf9f441fdaa6ccbde18b7569bd543f867078865c7f158832bbad472b2371c3`
- 参照SHA-256: `6544c8f245d0182d719e0b0109d42ee413d75eeae30a42bf90f6089c3418f905`
- 修正反復: 1 / 最大4（同版の撮影停止による再撮影は別記）
- 撮影: `build/vfx-shot/sigil-rifle-def/compare/`、標的HP: 200.0f
- [共通起点の比較画像](/evidence/sigil-rifle-def.png)
- [全操作の記録](/report/sigil-rifle)

強制発動の撮影は資源・クールダウン・入力の証明ではない。HP減少は10%・2倍のランダムクリティカルの影響を受け、実ヒット数と読み替えない。
