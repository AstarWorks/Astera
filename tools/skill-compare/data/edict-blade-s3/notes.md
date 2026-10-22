# The Index Proxy Don Quixote / S3

状態: astra2解析済・再制作中

原資料: [フレーム解析](/reference/edict-blade/s3.json) / [kit](/reference/edict-blade/kit.md)

| source seconds | source frame | game tick | range |
|---:|---:|---:|---|
| 20.086733 | 602 | 7 | dash_melee |
| 22.6226 | 678 | 57 | dash_melee |

1 tick = 50 ms。量子化は最近傍、半tickは切り上げ。原資料記載の接触遮蔽・コイン対応の不確実性は保持。旧intentは履歴資料であり、新しい再現結果ではない。

カメラ: 原資料のカット時刻と身長比を反映。数値のないshotはintentのnoteに暫定比を明記。姿勢・追従位置と実機での画面占有率は未合格。

## round 2 実装と検証

剣を廃止し、アイコン・装備3D・VFXを手枷と拳に変更。Qは枝状の黒い腕を展開し、変身演出自体にダメージを与えない。

比較用接触tick: [7, 57]。源のコイン対応にある不確実性は保持する。

状態: 現版撮影あり。S2最後の反対側からの打撃とS3の身体回転・着地姿勢が不足。拳の位置、白い軌跡、星状フラッシュの面積が未一致。青い残像は人物シルエットではない。

- 内容SHA-256: `240e461487dd2323a1937c504ec45993f839d1a07ce008d8885ac9e9506177c7`
- 参照SHA-256: `503c54d74ee0841e01ef8b2e14c1f07ce5ca89abd342c23e98fef1f29c568da0`
- 修正反復: 3 / 最大4（同版の撮影停止による再撮影は別記）
- 撮影: `build/vfx-shot/edict-s3/compare/`、標的HP: 181.304f
- [共通起点の比較画像](/evidence/edict-blade-s3.png)
- [全操作の記録](/report/edict-blade)

強制発動の撮影は資源・クールダウン・入力の証明ではない。HP減少は10%・2倍のランダムクリティカルの影響を受け、実ヒット数と読み替えない。
