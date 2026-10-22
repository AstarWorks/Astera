# The Index Proxy Don Quixote / S1

状態: astra2解析済・再制作中

原資料: [フレーム解析](/reference/edict-blade/s1.json) / [kit](/reference/edict-blade/kit.md)

| source seconds | source frame | game tick | range |
|---:|---:|---:|---|
| 12.479133 | 374 | 9 | dash_melee |
| 13.146467 | 394 | 22 | melee |

1 tick = 50 ms。量子化は最近傍、半tickは切り上げ。原資料記載の接触遮蔽・コイン対応の不確実性は保持。旧intentは履歴資料であり、新しい再現結果ではない。

カメラ: 原資料のカット時刻と身長比を反映。数値のないshotはintentのnoteに暫定比を明記。姿勢・追従位置と実機での画面占有率は未合格。

## round 2 実装と検証

剣を廃止し、アイコン・装備3D・VFXを手枷と拳に変更。Qは枝状の黒い腕を展開し、変身演出自体にダメージを与えない。

比較用接触tick: [9, 22]。源のコイン対応にある不確実性は保持する。

状態: 現版撮影あり。S2最後の反対側からの打撃とS3の身体回転・着地姿勢が不足。拳の位置、白い軌跡、星状フラッシュの面積が未一致。青い残像は人物シルエットではない。

- 内容SHA-256: `571d7f65083923311f8ab5c51ea5afddd0774ba702e4c26c917120e2e88f7830`
- 参照SHA-256: `982bc576e008ff74a2cbe397c033e0c442cb13ae5008a3b127c739e7d227d230`
- 修正反復: 4 / 最大4（同版の撮影停止による再撮影は別記）
- 撮影: `build/vfx-shot/edict-s1/compare/`、標的HP: 162.608f
- [共通起点の比較画像](/evidence/edict-blade-s1.png)
- [全操作の記録](/report/edict-blade)

強制発動の撮影は資源・クールダウン・入力の証明ではない。HP減少は10%・2倍のランダムクリティカルの影響を受け、実ヒット数と読み替えない。
