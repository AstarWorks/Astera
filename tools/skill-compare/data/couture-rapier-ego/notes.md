# The Alteration Shop Rodion / EGO

状態: wiki由来・原動画発動未確認

原資料: [フレーム解析](/reference/couture-rapier/ego.json) / [kit](/reference/couture-rapier/kit.md)

| source seconds | source frame | game tick | range |
|---:|---:|---:|---|

1 tick = 50 ms。量子化は最近傍、半tickは切り上げ。原資料記載の接触遮蔽・コイン対応の不確実性は保持。旧intentは履歴資料であり、新しい再現結果ではない。

## round 2 実装と検証

別の味方の金色ドームを除外。アイコン・装備3D・VFXを大鋏に変更し、開閉・遠隔の鋏・落下する鋏を再構成。

比較用接触tick: 原動画の攻撃接触なし。源のコイン対応にある不確実性は保持する。

状態: 現版撮影あり。原動画でこの操作は未観測。wiki由来のAstera向け設計であり、原作再現の合格対象に含めない。

- 内容SHA-256: `a395983bd03c42e335e45770c446f611a5a0b28a4f691504c96c49ac8576eee1`
- 参照SHA-256: `f6ac960b3fd1e47999ff1fb5a53da9c280b5c635970968ec944a3bbdb909545b`
- 修正反復: 3 / 最大4（同版の撮影停止による再撮影は別記）
- 撮影: `build/vfx-shot/couture-rapier-ego/compare/`、標的HP: 165.56f
- [共通起点の比較画像](/evidence/couture-rapier-ego.png)
- [全操作の記録](/report/couture-rapier)

強制発動の撮影は資源・クールダウン・入力の証明ではない。HP減少は10%・2倍のランダムクリティカルの影響を受け、実ヒット数と読み替えない。
