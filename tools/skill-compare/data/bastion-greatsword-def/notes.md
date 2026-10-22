# Zwei West Section 3 Ishmael / DEF

状態: astra2解析済・再制作中

原資料: [フレーム解析](/reference/bastion-greatsword/def.json) / [kit](/reference/bastion-greatsword/kit.md)

| source seconds | source frame | game tick | range |
|---:|---:|---:|---|

1 tick = 50 ms。量子化は最近傍、半tickは切り上げ。原資料記載の接触遮蔽・コイン対応の不確実性は保持。旧intentは履歴資料であり、新しい再現結果ではない。

## round 2 実装と検証

S1/S2/S3の接触時刻を再解析に合わせ、薙ぎの幅・薄さを修正。DEFは観測された防御区間に合わせた。

比較用接触tick: 原動画の攻撃接触なし。源のコイン対応にある不確実性は保持する。

状態: 現版撮影あり。身体の低い踏み込み・両手の上段姿勢・原動画の極端な寄りを再現できていない。斬撃と柱の輪郭、光量にも差がある。

- 内容SHA-256: `b85080deaf351bce967f5457e644d82bb8168c4e145fe2f536f7b63af08e28f3`
- 参照SHA-256: `4daf8ac4dcb8b285f47cdfb73bd29e4a631a0880ce587022a807ec00a77190cd`
- 修正反復: 1 / 最大4（同版の撮影停止による再撮影は別記）
- 撮影: `build/vfx-shot/bastion-greatsword-def/compare/`、標的HP: 200.0f
- [共通起点の比較画像](/evidence/bastion-greatsword-def.png)
- [全操作の記録](/report/bastion-greatsword)

強制発動の撮影は資源・クールダウン・入力の証明ではない。HP減少は10%・2倍のランダムクリティカルの影響を受け、実ヒット数と読み替えない。
