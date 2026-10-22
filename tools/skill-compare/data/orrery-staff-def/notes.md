# R Corp. 4th Pack Reindeer Rodion / DEF

状態: wiki由来・原動画発動未確認

原資料: [フレーム解析](/reference/orrery-staff/def.json) / [kit](/reference/orrery-staff/kit.md)

| source seconds | source frame | game tick | range |
|---:|---:|---:|---|

1 tick = 50 ms。量子化は最近傍、半tickは切り上げ。原資料記載の接触遮蔽・コイン対応の不確実性は保持。旧intentは履歴資料であり、新しい再現結果ではない。

## round 2 実装と検証

S1のロッド近接、S2の標的AoE、S3の4接触ビームへ訂正。S1の重複ロッド表示を削除。Qは狭いconeが正面の標的を外していたため、ビームの射線と同じforward_rayへ統一した。

比較用接触tick: 原動画の攻撃接触なし。源のコイン対応にある不確実性は保持する。

状態: 現版撮影あり。原動画でこの操作は未観測。wiki由来のAstera向け設計であり、原作再現の合格対象に含めない。

- 内容SHA-256: `5b7b1f57231eb60ee328abc36ed771d193ea86b89c882066f629c857995826b2`
- 参照SHA-256: `2e144cddf8f0adfbc3f737f99bcc64bee5c4be4550b10ff3df8a5fa648fbbfa8`
- 修正反復: 1 / 最大4（同版の撮影停止による再撮影は別記）
- 撮影: `build/vfx-shot/orrery-def/compare/`、標的HP: 200.0f
- [共通起点の比較画像](/evidence/orrery-staff-def.png)
- [全操作の記録](/report/orrery-staff)

強制発動の撮影は資源・クールダウン・入力の証明ではない。HP減少は10%・2倍のランダムクリティカルの影響を受け、実ヒット数と読み替えない。
