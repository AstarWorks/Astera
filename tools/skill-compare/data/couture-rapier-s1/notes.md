# The Alteration Shop Rodion / S1

状態: astra2解析済・再制作中

原資料: [フレーム解析](/reference/couture-rapier/s1.json) / [kit](/reference/couture-rapier/kit.md)

| source seconds | source frame | game tick | range |
|---:|---:|---:|---|
| 13.079733 | 392 | 13 | dash_melee |

1 tick = 50 ms。量子化は最近傍、半tickは切り上げ。原資料記載の接触遮蔽・コイン対応の不確実性は保持。旧intentは履歴資料であり、新しい再現結果ではない。

カメラ: 原資料のカット時刻と身長比を反映。数値のないshotはintentのnoteに暫定比を明記。姿勢・追従位置と実機での画面占有率は未合格。

## round 2 実装と検証

別の味方の金色ドームを除外。アイコン・装備3D・VFXを大鋏に変更し、開閉・遠隔の鋏・落下する鋏を再構成。

比較用接触tick: [13]。源のコイン対応にある不確実性は保持する。

状態: 現版撮影あり。身体の向き替え、指先の鋏操作、白い光帯の厚み・連続性、花飾りとロゼットの細部が不足。カット時刻は反映したが、寄りの画面占有率は未一致。

- 内容SHA-256: `6b898aebdc7934209e8ac18bdd5798eecb3a45888bffb14243c87f8e2e66f19c`
- 参照SHA-256: `3a88f54825eec141e8c687f87ded3861206e760c42c17897d7061cfca2ce6b9a`
- 修正反復: 4 / 最大4（同版の撮影停止による再撮影は別記）
- 撮影: `build/vfx-shot/couture-rapier-s1/compare/`、標的HP: 192.128f
- [共通起点の比較画像](/evidence/couture-rapier-s1.png)
- [全操作の記録](/report/couture-rapier)

強制発動の撮影は資源・クールダウン・入力の証明ではない。HP減少は10%・2倍のランダムクリティカルの影響を受け、実ヒット数と読み替えない。
