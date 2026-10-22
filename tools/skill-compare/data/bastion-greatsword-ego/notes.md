# Zwei West Section 3 Ishmael / EGO

状態: wiki由来・原動画発動未確認

原資料: [フレーム解析](/reference/bastion-greatsword/ego.json) / [kit](/reference/bastion-greatsword/kit.md)

| source seconds | source frame | game tick | range |
|---:|---:|---:|---|

1 tick = 50 ms。量子化は最近傍、半tickは切り上げ。原資料記載の接触遮蔽・コイン対応の不確実性は保持。旧intentは履歴資料であり、新しい再現結果ではない。

## round 2 実装と検証

S1/S2/S3の接触時刻を再解析に合わせ、薙ぎの幅・薄さを修正。DEFは観測された防御区間に合わせた。

比較用接触tick: 原動画の攻撃接触なし。源のコイン対応にある不確実性は保持する。

状態: 現版撮影あり。原動画でこの操作は未観測。wiki由来のAstera向け設計であり、原作再現の合格対象に含めない。

- 内容SHA-256: `e346c27b6bcf401c685f323dd47e744b85c0e21e92ea23e575e6bd5606273836`
- 参照SHA-256: `a40478a425ea3fcf5bbad88a61b0bc624b7e2e17e746b01723ac5a046b6ce99a`
- 修正反復: 1 / 最大4（同版の撮影停止による再撮影は別記）
- 撮影: `build/vfx-shot/bastion-greatsword-ego/compare/`、標的HP: 172.448f
- [共通起点の比較画像](/evidence/bastion-greatsword-ego.png)
- [全操作の記録](/report/bastion-greatsword)

強制発動の撮影は資源・クールダウン・入力の証明ではない。HP減少は10%・2倍のランダムクリティカルの影響を受け、実ヒット数と読み替えない。
