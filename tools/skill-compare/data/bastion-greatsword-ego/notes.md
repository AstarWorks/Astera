# Zwei West Section 3 Ishmael / E.G.O 城塞の暁 (独自設計)

**参照映像なし・独自設計**。動画 `Svaaq9F7Ty8` に E.G.O の発動は見当たらない (31.767 以降は紹介の長いイラスト = outro)。case の start/end (0–3.8 s) は比較アプリの枠を満たすためだけの仮置きで、原動画との一致は主張しない。
bind: Q `bastion-greatsword-ego`。

## 設計 (動画からではなく skill 名 / wiki kit の説明から)

| tick | 内容 | 実装 |
|---|---|---|
| t0–30 | 大盾 (半透明障壁) を掲げる | `bastion-ego-guard` (shield 30 tick) |
| t14 | 大剣を構える音 | play_sound |
| t20–26 | 前へ跳ぶ (キャスター移動) | `propel` up 0.3 (t20) + `dash_strike` 2.8 block / 6 tick (stop 1.6) |
| t27–31 | 大剣を高く掲げ床へ突き立てる | `bastion-ego-slam` の rig (掲げ → 叩きつけ) |
| t31 | 黄金の光柱 (高さ 10) + 水平の円盤 + 四方へ走る稲妻 | VFX + damage 28 (charge 消費) + knockback 1.6 + camera shake |

### キャスター移動 (設計値)
- 前方へ 2.8 block (t20–26)、頂点で高さ約 0.9 block。VFX はウォークを終えた t27 の位置に置く (`follow:false`)。

## 状態
独自設計・動作確認中 (反復 2)。操作 (Q + E.G.O ゲージ) と拒否条件の通常入力確認は未実施。原作再現とは呼ばない。

## 反復ログ
- 反復1: 障壁→跳躍→光柱+稲妻は動作。稲妻の本数・色は仮。
- 反復2: 星グリフ小型化、粒子クリット系化。
