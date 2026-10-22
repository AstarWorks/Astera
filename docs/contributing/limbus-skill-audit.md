# Limbus武器スキル修正・初期監査

確認日: 2026-10-23。これは既存REPORTと作業ツリーの監査であり、今回の実機再検証結果ではない。

手順: [制作・修正手順](limbus-skill-reproduction.md)。他セッションの作業は進行中の可能性があるため、下表は更新される。

| 武器 | 担当worktreeのHEAD | mainバインド数 | 担当版バインド数 | 担当版の未コミット変更 |
|---|---|---:|---:|---:|
| bastion-greatsword | `f8dfce92` | 4 | 5 | 0 |
| couture-rapier | `d437a1e5` | 4 | 5 | 0 |
| crimson-chain-axe | `8c98ad21` | 4 | 5 | 26 |
| edict-blade | `ff7a5ab6` | 4 | 5 | 0 |
| ember-gauntlet | `32b748a9` | 4 | 5 | 0 |
| gossamer-katana | `6f16080b` | 4 | 5 | 13 |
| orrery-staff | `a81fdae1` | 4 | 5 | 0 |
| sigil-rifle | `0d5f98d7` | 4 | 5 | 0 |
| vendetta-rifle | `3abefbd5` | 5 | 6 | 7 |
| verdict-hammer | `027331bc` | 4 | 5 | 0 |

## 確認できた共通課題

- mainと担当worktreeで内容・バインドが異なる。担当版にはQがあるがmainにはまだ無い武器もある。比較画面のケースがあることは、その実装がmainへ反映された証明ではない。
- 複数のREPORTに「4回で終了・視覚一致は未承認」とある。反復回数を合格扱いしない。
- 装備時にskill damageがLEFT_CLICKへ再入する問題はmainの`795f836c`で修正済み。修正前の空手・skill-tester撮影は、修正後の装備確認を代替しない。
- 初期監査の訂正: `PaperPlayerPoseLookup`単体はplayer専用だが、現在のCombatWiringは`EntityFallbackPoseLookup`をdispatcherへ渡しておりmob位置を補完する。Edictの旧REPORTにある固定位置回避が現在も必要とは限らない。
- `TargeterResolver`のNearbyEntities分岐では`maxCount`が適用されていない。少数対象を前提にした表示予算・命中対象を再確認する。
- 比較一覧の`SLOTS`は`drop`を含めず、`sneak_drop`をQと表示している。実際のQ/Shift+Qと不一致。
- 実機の表示遅延とwall-clockカメラのズレが複数報告にある。各ヒットのoffsetで隠さず、同期問題を先に切り分ける。
- DEF/Qの原動画が無い、あるいは技の同定が暫定のケースがある。独自設計と再現対象を区別する。

## 修正順序

1. 他セッションとの担当・引き継ぎを確定し、作業中の差分を保全する。
2. 比較一覧のバインド、対象位置・対象数、装備時ダメージの共通経路を確認する。
3. 担当版の原動画観察表を確認し、技同定・接触回数・参照なしの範囲を確定する。
4. 各武器の動作・主形状・射程と命中位置を直し、実装版を固定して撮影する。
5. 全バインドの結果を記録し、未検証・残差を明示する。
