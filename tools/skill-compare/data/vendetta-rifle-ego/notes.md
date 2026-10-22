# The Thumb East Soldato II Sinclair / EGO

状態: wiki由来・原動画発動未確認

原資料: [フレーム解析](/reference/vendetta-rifle/ego.json) / [kit](/reference/vendetta-rifle/kit.md)

| source seconds | source frame | game tick | range |
|---:|---:|---:|---|

1 tick = 50 ms。量子化は最近傍、半tickは切り上げ。原資料記載の接触遮蔽・コイン対応の不確実性は保持。旧intentは履歴資料であり、新しい再現結果ではない。

## round 2 実装と検証

参照主体をThumb East Soldato II Sinclairへ訂正。銃剣の2/2/3段と防御・Shift+Qのリロードを対応づけた。

比較用接触tick: 原動画の攻撃接触なし。源のコイン対応にある不確実性は保持する。

状態: 現版撮影あり。原動画でこの操作は未観測。wiki由来のAstera向け設計であり、原作再現の合格対象に含めない。

- 内容SHA-256: `75876a642a2de47492761d1b0a868f35189aa624158deea7e669c1b2966d9b36`
- 参照SHA-256: `d77c558e878dd9e86b7e64613f7eea342bc39c1a5155d730db8b3c222ec9c579`
- 修正反復: 1 / 最大4（同版の撮影停止による再撮影は別記）
- 撮影: `build/vfx-shot/vendetta-rifle-ego/compare/`、標的HP: 188.384f
- [共通起点の比較画像](/evidence/vendetta-rifle-ego.png)
- [全操作の記録](/report/vendetta-rifle)

強制発動の撮影は資源・クールダウン・入力の証明ではない。HP減少は10%・2倍のランダムクリティカルの影響を受け、実ヒット数と読み替えない。
