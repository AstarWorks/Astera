# R Corp. 4th Pack Reindeer Rodion / S2

状態: astra2解析済・再制作中

原資料: [フレーム解析](/reference/orrery-staff/s2.json) / [kit](/reference/orrery-staff/kit.md)

| source seconds | source frame | game tick | range |
|---:|---:|---:|---|
| 18.985633 | 569 | 35 | aoe_at_target |
| 20.253567 | 607 | 61 | aoe_at_target |

1 tick = 50 ms。量子化は最近傍、半tickは切り上げ。原資料記載の接触遮蔽・コイン対応の不確実性は保持。旧intentは履歴資料であり、新しい再現結果ではない。

## round 2 実装と検証

S1のロッド近接、S2の標的AoE、S3の4接触ビームへ訂正。S1の重複ロッド表示を削除。Qは狭いconeが正面の標的を外していたため、ビームの射線と同じforward_rayへ統一した。

比較用接触tick: [35, 61]。源のコイン対応にある不確実性は保持する。

状態: 現版撮影あり。ロッド先端の構え、輪の密度と重なり、ビーム発光と発砲時刻の実機表示遅延、カメラ寄りが未一致。

- 内容SHA-256: `810b69879c53c0fe023849afc1a10d1f1779186e827991ca87a6f5974d6f82b5`
- 参照SHA-256: `6737ff3fd4f9e53d06a7489c746c48b7f11a053a63cb1e9f6e887fe232da6eab`
- 修正反復: 1 / 最大4（同版の撮影停止による再撮影は別記）
- 撮影: `build/vfx-shot/orrery-s2/compare/`、標的HP: 170.48f
- [共通起点の比較画像](/evidence/orrery-staff-s2.png)
- [全操作の記録](/report/orrery-staff)

強制発動の撮影は資源・クールダウン・入力の証明ではない。HP減少は10%・2倍のランダムクリティカルの影響を受け、実ヒット数と読み替えない。
