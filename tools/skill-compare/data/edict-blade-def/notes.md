# The Index Proxy Don Quixote / DEF

状態: wiki由来・原動画発動未確認

原資料: [フレーム解析](/reference/edict-blade/def.json) / [kit](/reference/edict-blade/kit.md)

| source seconds | source frame | game tick | range |
|---:|---:|---:|---|

1 tick = 50 ms。量子化は最近傍、半tickは切り上げ。原資料記載の接触遮蔽・コイン対応の不確実性は保持。旧intentは履歴資料であり、新しい再現結果ではない。

## round 2 実装と検証

剣を廃止し、アイコン・装備3D・VFXを手枷と拳に変更。Qは枝状の黒い腕を展開し、変身演出自体にダメージを与えない。

比較用接触tick: 原動画の攻撃接触なし。源のコイン対応にある不確実性は保持する。

状態: 現版撮影あり。原動画でこの操作は未観測。wiki由来のAstera向け設計であり、原作再現の合格対象に含めない。

- 内容SHA-256: `fccc80a692a2d83ea204725c3be9afa01659b0ab8eda1fe2eda360d453e1b9ff`
- 参照SHA-256: `dd61cce81b54d08f7528f43aa673eedea5bfe0ea2dfcffc30cd666f6ff404260`
- 修正反復: 3 / 最大4（同版の撮影停止による再撮影は別記）
- 撮影: `build/vfx-shot/edict-def/compare/`、標的HP: 185.24f
- [共通起点の比較画像](/evidence/edict-blade-def.png)
- [全操作の記録](/report/edict-blade)

強制発動の撮影は資源・クールダウン・入力の証明ではない。HP減少は10%・2倍のランダムクリティカルの影響を受け、実ヒット数と読み替えない。
