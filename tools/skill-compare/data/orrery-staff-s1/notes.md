# R Corp. 4th Pack Reindeer Rodion / S1

状態: astra2解析済・再制作中

原資料: [フレーム解析](/reference/orrery-staff/s1.json) / [kit](/reference/orrery-staff/kit.md)

| source seconds | source frame | game tick | range |
|---:|---:|---:|---|
| 13.4134 | 402 | 24 | dash_melee |
| 14.948267 | 448 | 55 | melee |

1 tick = 50 ms。量子化は最近傍、半tickは切り上げ。原資料記載の接触遮蔽・コイン対応の不確実性は保持。旧intentは履歴資料であり、新しい再現結果ではない。

## round 2 実装と検証

S1のロッド近接、S2の標的AoE、S3の4接触ビームへ訂正。S1の重複ロッド表示を削除。Qは狭いconeが正面の標的を外していたため、ビームの射線と同じforward_rayへ統一した。

比較用接触tick: [24, 55]。源のコイン対応にある不確実性は保持する。

状態: 現版撮影あり。ロッド先端の構え、輪の密度と重なり、ビーム発光と発砲時刻の実機表示遅延、カメラ寄りが未一致。

- 内容SHA-256: `ed5b119b1f2533879870aadf77a85e909606c33431a5f6653b337221c156587a`
- 参照SHA-256: `fbf0e6d15634cf9b327fa2ad16431e36532685609749bf7c38c5d692b76e953b`
- 修正反復: 2 / 最大4（同版の撮影停止による再撮影は別記）
- 撮影: `build/vfx-shot/orrery-s1/compare/`、標的HP: 184.256f
- [共通起点の比較画像](/evidence/orrery-staff-s1.png)
- [全操作の記録](/report/orrery-staff)

強制発動の撮影は資源・クールダウン・入力の証明ではない。HP減少は10%・2倍のランダムクリティカルの影響を受け、実ヒット数と読み替えない。
