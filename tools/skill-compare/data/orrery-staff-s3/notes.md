# R Corp. 4th Pack Reindeer Rodion / S3

状態: astra2解析済・再制作中

原資料: [フレーム解析](/reference/orrery-staff/s3.json) / [kit](/reference/orrery-staff/kit.md)

| source seconds | source frame | game tick | range |
|---:|---:|---:|---|
| 25.792433 | 773 | 45 | beam |
| 26.5265 | 795 | 60 | beam |
| 27.1271 | 813 | 72 | beam |
| 30.663967 | 919 | 143 | beam |

1 tick = 50 ms。量子化は最近傍、半tickは切り上げ。原資料記載の接触遮蔽・コイン対応の不確実性は保持。旧intentは履歴資料であり、新しい再現結果ではない。

## round 2 実装と検証

S1のロッド近接、S2の標的AoE、S3の4接触ビームへ訂正。S1の重複ロッド表示を削除。Qは狭いconeが正面の標的を外していたため、ビームの射線と同じforward_rayへ統一した。

比較用接触tick: [45, 60, 72, 143]。源のコイン対応にある不確実性は保持する。

状態: 現版撮影あり。ロッド先端の構え、輪の密度と重なり、ビーム発光と発砲時刻の実機表示遅延、カメラ寄りが未一致。

- 内容SHA-256: `150dc0c1f8c1db32debd54a0928a9a57e2c6d10fbd915b86f7e3d03e42932f26`
- 参照SHA-256: `37cecbf269279ceeb30fc8758063b98bfd2b4119e0e80d61007e51ec20da4f94`
- 修正反復: 1 / 最大4（同版の撮影停止による再撮影は別記）
- 撮影: `build/vfx-shot/orrery-s3/compare/`、標的HP: 167.52798f
- [共通起点の比較画像](/evidence/orrery-staff-s3.png)
- [全操作の記録](/report/orrery-staff)

強制発動の撮影は資源・クールダウン・入力の証明ではない。HP減少は10%・2倍のランダムクリティカルの影響を受け、実ヒット数と読み替えない。
