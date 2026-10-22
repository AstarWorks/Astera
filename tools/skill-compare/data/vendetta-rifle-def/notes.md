# The Thumb East Soldato II Sinclair / DEF

状態: wiki由来・原動画発動未確認

原資料: [フレーム解析](/reference/vendetta-rifle/def.json) / [kit](/reference/vendetta-rifle/kit.md)

| source seconds | source frame | game tick | range |
|---:|---:|---:|---|

1 tick = 50 ms。量子化は最近傍、半tickは切り上げ。原資料記載の接触遮蔽・コイン対応の不確実性は保持。旧intentは履歴資料であり、新しい再現結果ではない。

## round 2 実装と検証

参照主体をThumb East Soldato II Sinclairへ訂正。銃剣の2/2/3段と防御・Shift+Qのリロードを対応づけた。

比較用接触tick: 原動画の攻撃接触なし。源のコイン対応にある不確実性は保持する。

状態: 現版撮影あり。原動画でこの操作は未観測。wiki由来のAstera向け設計であり、原作再現の合格対象に含めない。

- 内容SHA-256: `4998a4c505f7f0d93d4faf679c023fbfad1c7a5b2e6f245c341fa7d57ead2693`
- 参照SHA-256: `4da8532b47aedf501b07ed359fa008f7f5cb08e650b5275ca53198fca7f49588`
- 修正反復: 1 / 最大4（同版の撮影停止による再撮影は別記）
- 撮影: `build/vfx-shot/vendetta-rifle-def/compare/`、標的HP: 200.0f
- [共通起点の比較画像](/evidence/vendetta-rifle-def.png)
- [全操作の記録](/report/vendetta-rifle)

強制発動の撮影は資源・クールダウン・入力の証明ではない。HP減少は10%・2倍のランダムクリティカルの影響を受け、実ヒット数と読み替えない。
