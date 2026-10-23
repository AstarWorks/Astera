# Limbus参照武器スキルの制作・修正手順

この文書を制作フローの入口とする。目的は、参照動画で確認できる技の動き・形・命中をMinecraft上で再現し、実武器を装備した実機映像で確認すること。YAMLが読める、撮影できる、所定回数を修正した、だけでは完了しない。

## 1. 対象と作業場所を確定する

- ADR-0230の10武器を対象に、`content/items/<weapon>.yaml` の `skills` を全件列挙する。S1/S2/S3/DEF/Qに加え、リロードなどの追加バインドも漏らさない。
- 作業開始時のコミット、担当、worktree、未コミット変更を記録する。同時編集中なら差分を保全して隔離worktreeで修正し、統合時に衝突を確認する。ユーザーが優先作業を指示済みなら、引き継ぎ確認を理由に停止しない。
- 比較アプリは通常、複数worktreeを横断し、revと更新時刻でケースを選ぶ。統合レビューでは `SKILL_COMPARE_CANONICAL=1` で配信checkoutのケースを優先する。レビュー時は表示元ブランチ、実装コミット、撮影元を確認する。別版の画像で合格させない。

## 2. 原動画を確定する

round 2の基準は引き継ぎ書が指定する`pm/astra2/<videoId>/`の再解析（segments、各slot、notes）。旧intentを正解として引き継がない。大鋏・手枷付きの拳・Sinclairの銃剣への変更、攻撃主体・近接/遠隔・native fpsを先に確認する。動画にない技はwiki kitから組み、caseの`status`を`from-wiki`とする。

各技について `tools/skill-compare/data/<case>/case.json` に動画ID、技の開始・終了、実装skill ID、各接触時刻を登録する。参照区間はnative fpsでコマ送りして確認する。

```sh
make skill-compare-case CASE=<case> VIDEO=<id> START=<seconds> END=<seconds> SKILL=<skill-id> WEAPON=<item-id>
```

`notes.md` に、動画のどの区間がどの技なのか、その根拠と不確実性を書く。DEFやQが動画にない場合は「参照なし・独自設計」と明記する。紹介動画中の長い演出を根拠なくE.G.Oと断定しない。独自設計は再現合格の対象にせず、操作・可読性・命中・後片付けを別に確認する。

## 3. 実装前に観察表を作る

各技の `notes.md` に以下を記録する。推測には推測と書き、測れていない数値を確定値にしない。

| 時刻・区間 | 原動画の観察 | Minecraftでの目標 | 根拠フレーム |
|---|---|---|---|
| 予備動作 | 武器姿勢、足位置、溜め | 始点・向き・長さ・時間 | 動画秒 / フレーム |
| 各接触 | 接触回数、位置、軌跡、被弾反応 | damage tick、到達距離、VFXアンカー | 動画秒 / フレーム |
| 決め | 主形状、配色、画面占有率 | 寸法、輪郭、暗部、閃光 | 動画秒 / フレーム |
| 余韻 | 残像、飛散、武器の戻り | 寿命、フェード、終了位置 | 動画秒 / フレーム |

- 基準時間は技開始からの経過時間。`tick = floor((video_seconds - skill_start_seconds) * 20 + 0.5)`。ケース開始と技開始が異なる場合は両方を記録する。
- 接触イベントとコイン数は別々に観察する。多段演出や装飾フラッシュを自動的に追加ダメージにしない。
- キャラクター身長等の共通基準で大きさを測る。カメラ変更による見かけの変化と、物体自体の拡縮を区別する。
- caseの `intent.file` が指すJSON（round 2では `intent-round2.json`）にカメラ・人物・武器・演出を記す。intentは原動画の解釈であり、正しさの証明ではない。実装に合わせて目標を変更しない。
- Minecraft向けの変更（操作、状態異常、時間、射程等）は理由と原作との差を一覧にする。修正漏れを「仕様」として扱わない。

## 4. 実装する

YAMLの形式は [add-a-skill-yaml.md](add-a-skill-yaml.md)、形・色・武器rig・trailは [vfx-art-direction.md](vfx-art-direction.md) に従う。

1. まず武器の始点・支点・振る方向・接触位置・移動・ヒット回数を合わせる。
2. 次に主役の輪郭、面積、傾き、色、前後関係を合わせる。
3. 最後に火花、煙、閃光、音、余韻を足す。装飾で主形状のズレを隠さない。
4. 命中VFXとdamageを同tick・同地点に置く。射程だけ延ばして見えていない接触を成立させない。移動・ノックバック後の位置でも一致させる。
5. 追従・固定座標・対象アンカーを選び分ける。風切りが術者について移動する、遠方の敵に固定距離の爆発が出る、といった不整合を残さない。
6. 重なりを含めDisplay上限を計算する。遅延した別インスタンス、rigのtrail、freezeによる寿命延長も含める。パケット表示はサーバのentity数だけで実測したと断定しない。

確認済みの落とし穴:

- 同じ接触のdamage・火花・硬直それぞれに`random`を書くと、別の敵を選び得る。単体の近接攻撃には`cone.max_count: 1`で最寄りを選び、敵2体でも確認する。ノックバックは命中演出を出した後に行う。
- `nearby_entities.max_count`は術者除外後の件数。範囲内の人数に比例して演出を増やさない。
- 飛散の`drag`は失う速度の割合。0.95は95%維持ではなく95%減衰。
- `billboard: center`の板の向きに術者のyawを重ねない。位置の回転と、板面のカメラ追従を区別する。
- TextDisplayの中心補正は実機の描画寸法に依存する。サイズ変更で中心がずれる場合、各武器の位置を逆補正する前に共通描画を較正する。

構文・参照検証は `./gradlew :tools:content-lint:run` を使う。実行が許可されない環境では未実行と記録する。lintはruntimeロードや見た目の合格を代替しない。

今回のround 2では、引き継ぎ書の指定に従いテストスイートを実行しない。検証はcontent-lint、runtimeロード、実武器を装備した実クライアント撮影、原動画との比較で行う。通常入力・資源不足・全バインドの確認も実機で行う。

## 5. 実武器を装備して撮影する

詳細は [vfx-shot README](../../tools/vfx-shot/README.md)。撮影はDockerとビルドを使うため、実行制約を事前に確認する。

```sh
SKILL=<skill-id> ITEM=<item-id> VIEW=compare CASE=<case> bash tools/vfx-shot/vfx-shot.sh
make vfx-shot SKILL=<skill-id> ITEM=<item-id> VIEW=caster TICKS=<recovery-end-plus-margin>
make vfx-shot SKILL=<skill-id> ITEM=<item-id> VIEW=opponent TICKS=<recovery-end-plus-margin>
make vfx-shot SKILL=<skill-id> ITEM=<item-id> VIEW=side TICKS=<recovery-end-plus-margin>
```

- `ITEM` は必ず明示する。特にQの `-ego` は既定の武器ID推定に頼らない。
- 実装コミットと未コミット差分、撮影コマンド、pack版、撮影日時、出力先をnotesに記録する。古い画像を新実装の証拠にしない。
- 原動画比較用カメラに加え、術者・相手・横の視点で確認する。spectatorには術者だけのtitle overlayが見えない。
- 撮影前に対象HP、位置、術者の状態・資源を揃える。不要な対象・残骸が混ざる映像は判定に使わない。
- 空手やskill-testerでの撮影は原因切り分け用。装備時の受け入れ確認の代わりにはしない。
- `--force` 撮影はクールダウン・資源不足・操作バインドの検証にならない。通常入力でS1/S2/S3/DEF/Q等の発動と拒否条件を別途確認する。
- headless撮影の時刻はwall-clockでありサーバtickそのものではない。同期ズレは共通の基準で調査する。各ヒットごとのoffset調整でズレを隠さない。
- 同じフレームが続く場合は通信待ちと撮影側の停止を区別する。`captureQuality`の警告を確認し、実際に描画停止していた映像は`invalid: true`で除外して再撮影する。
- 現行runtimeは10%・2倍のランダムクリティカルを使う（`CombatWiring`）。標的HPの総減少だけではヒット回数を確定できない。HPは補助記録とし、各接触の映像や実測イベントと照合する。`effect clear`はvanilla効果のみで、Asteraの状態異常を消す操作ではない。

## 6. 比較し、原因を分けて直す

```sh
make skill-pairs CASE=<case> AT="<seconds>:windup <seconds>:hit1 <seconds>:finish <seconds>:recovery"
make skill-compare
```

生成したペア画像を実際に開き、通常速度とコマ送りの両方を見る。接触の瞬間だけでなく、その直前・直後も確認する。

| 判定項目 | 合格条件 |
|---|---|
| 対象技 | 動画区間とバインド・実装IDが一致する |
| 動作 | 移動方向、振る方向、接触順、回数、戻りが観察表と一致する |
| 形 | 主形状・姿勢・寸法・配置・色の目標を満たす。板の向き違い、遮蔽、浮き、欠落がない |
| 時間 | 共通起点で各ビートが合う。20TPS丸めは最大25ms。実機遅延は別途測り、不明なら時間合格にしない |
| 命中 | 表示と判定が一致し、装備状態で期待した回数だけ命中する |
| 遊びやすさ | 術者と相手の視界、予兆、危険範囲、後片付けが適切 |
| 予算 | 同時表示と寿命が基準内。例外は理由と影響を記録する |

差分ごとに「原動画の事実 / 現在の見え方 / 原因 / 次の修正 / 再撮影結果」を記す。原因はコンテンツ、共通runtime、撮影同期、未対応表現、参照不足を区別する。「エンジンの限界」は確認した根拠を添える。姿勢やアンカーの調整不足を限界として処理しない。

2026-10-23のユーザー追指示で、round 2の最大4反復という終了条件は撤廃した。観察→実装→実機撮影→比較レビュー→改修を、一致が証拠付きで確認できるまで繰り返す。反復回数は継続して記録し、回数到達で終了・合格としない。コンテンツだけで残る身体動作・条件分岐・変身等も実装対象として調査する。共通runtimeを直した場合は影響する全武器を装備状態で再確認する。参照未観測の技に原作一致の判定を付けない。

## 7. 完了と引き継ぎ

各技のnotes/reportには、最低限次の状態を残す（比較アプリの既存status表示とは別に明記する）。

- **未分析**：参照または観察表が不足。
- **実装済・未検証**：実装はあるが、現在版の実機確認がない。
- **検証不合格**：確認した不一致が残る。残差と次の修正を列挙する。
- **再現合格**：参照あり、上の項目を確認し、証拠画像と実装版が結び付いている。
- **独自設計・動作確認済**：参照のない技について操作・表示・命中等を確認済み。原作再現とは呼ばない。

武器単位のREPORTに全バインドの結果を並べ、未確認を空欄にしない。最新の比較画像、観察表、既知の制約、検証コマンドと結果へリンクする。全武器修正の完了は、全バインドの処理と残差の説明が揃い、未検証・不合格を「完了」に含めていないことが条件。

## 比較画面の版と再生

- 51操作を実際のitem YAMLから一覧化する。S3等の略称だけで紐づけず `skill.asteraSkillId` と `weapon` を記録する。
- 原資料のスナップショットは `tools/skill-compare/data/reference-analysis/<weapon>/`、開始・接触の索引は `round2-sources.json`。原資料の不確実性を消さない。
- caseの `referenceRevision` は原資料のSHA-256、`contentRevision` はskillと参照VFX・内部skill・itemの内容ハッシュ。実機 `meta.json` に両方を記録する。一致しない撮影は比較欄へ出さない。
- スプライトを使うVFXの内容版には `content/sprites/<id>.yaml`、`content/fonts/vfx/<id>.json`、生成PNGも含める。実機確認時は配信packのSHA-256を別途記録する。
- この内容ハッシュだけではruntime jar・全パーティクル定義・リソースパックの同一性までは証明しない。これらを変更した場合も配備版を記録し、影響する技を撮り直す。`implementationCommit`は撮影ツール側のcheckoutを表し、サーバーjarの版を自動検出した値ではない。
- 切り出した原動画もvideoId/start/endが一致するものだけ使う。24fpsと29.97fpsを30fpsに再サンプリングしない。無音動画でも切り出せる。
- 原動画の全コマ一括ロードを避け、両側で共用する6件の読込キュー・48枚キャッシュ・先読みで再生する。次の両側の画像がそろうまで時刻を止め、「読み込み中」を表示する。失敗時は再試行する。
- 自動スローは初期オフ。オンの場合は命中付近を意図的に減速するため、読込待ちと区別して表示する。
- 撮影用dummyが術者へ反撃するとカメラ・命中判定を乱す。比較時はNoAIの標的で分離し、移動する敵への追撃は別途確認する。
- 実機欄は全フレームのWebPが揃えば軽量プレビューを使い、欠けていればPNGへ戻す。原寸PNGは計測用に残す。撮影版をURLに含め、再撮影前の画像キャッシュとの混在を防ぐ。
- PNGとWebPは同じ0始まりのフレーム番号を使う。枚数一致だけでは不十分で、PNG各ファイルに同番号のWebPがあることを確認する。再生成では余剰連番を削除し、撮影metaのプレビュー版を更新する。先頭・命中付近・末尾を両形式で照合する。
- 比較撮影の `director-timing.log` にcast応答と各camera cutの送信・応答時刻を残す。連続同一フレームが少なくても同期正常とは限らない。予定のpull-back時点でimpactの近景が残る等の遅延があれば、その映像は `captureQuality.invalid` として除外し再撮影する。RCON応答時刻だけではクライアント描画時刻を証明しない。

### 人物の姿勢をスキルから指定する

`play_animation` の `animation: crouch` は、プレイヤーの姿勢を `ticks` の間固定する。`delay_ticks` で開始を指定する。入力のShift状態を変更しないため、通常左クリックをShift+左へ切り替える操作ではない。

```yaml
- type: play_animation
  targets: {type: caster}
  animation: crouch
  ticks: {type: literal, value: 35}
  delay_ticks: {type: literal, value: 2}
```

終了時は元の姿勢と固定状態を復元する。重ねた場合は最新の予約だけが解除を行い、プラグイン停止時にも解除する。未知のanimation IDは実行失敗として記録する。現在のPaper実装はネイティブの低い姿勢のみで、任意の腕・脚・胴体の関節アニメーションを実装したものではない。原作の細かな振付をこれだけで合格にしない。


## 条件付き反撃の確認

反撃を固定delayの攻撃に置き換えない。`counter` は `window_ticks` 内に通った攻撃を受けたとき、`max_distance` 内の攻撃者に一度だけ `on_hit` を実行する。反撃対象は `target` で参照し、近傍の別の敵に置き換えない。遅延する命中には接触時の距離条件も付ける。現在の実装は被ダメージ型であり、攻撃同士を競り合わせるclashable counterの再現ではない。

実クライアント確認では、無被弾、窓内の二度の被弾、窓外の被弾、環境ダメージ、射程外、接触前に退避した攻撃者を分ける。二度目の被弾も実際にHPが減ったことを記録し、無敵時間で攻撃が無効だっただけの結果を一回限定の証拠にしない。近くに別の標的を置いて誤爆を確認し、モブ攻撃とプレイヤースキル攻撃の両経路を確認する。通常バインドの映像にも被弾の時刻と撮影用攻撃の条件を記録する。

原動画で未観測の防御技は、これらを通しても原作一致と認定しない。参照映像があるclashable counterには、その映像の競り合い・解決条件の観察を別途適用する。


## 追加形態・派生区間を対象から落とさない

S1/S2/S3/DEF/Qの基本バインドだけでは観測された全技の一覧にならない。`segments.json` の全区間と `extra-*.json` の対応を確認し、変身後の技・派生・別人物の行動・再演・同定未確定の区間を区別する。基本バインドの撮影数を全技の完了率にしない。

追加原資料22件を `tools/skill-compare/data/reference-analysis/` に保全し、`additional-coverage.json` に元資料のSHA256と未確認状態を記録した。この22件は22個の独立した技を意味しない。同定未確定・状態表示・繰り返しを含むため、区間ごとの審査が必要。

EdictのProcuration S1〜S3は追加比較ケースへ登録。参照は原動画のnative frame区間をそのまま抽出し、通常形態の実機映像を代用しない。実装前は「未実装・未接続」と表示する。変身の成立・継続・解除と、変身後の各バインドの実機撮影が揃うまでは完了にならない。


## 往復・斜めダッシュの方向

`dash_strike.local_direction` は術者基準の `{x, y, z}`。`+Z` が前、`+X` が左、`+Y` が上で、正規化後に `distance` を掛ける。省略時は従来の視線方向を使う。`follow_pitch: false` なら水平な視線を基準にし、`true` なら上下の視線も基準へ含める。ゼロベクトル・非有限値は使用しない。

`face_movement: true` は進行方向へ水平の向きを変える。視点のpitchは保つ。次のダッシュの相対方向は、変更後の向きを基準に解決されるため、往復の各区間で向きを確認する。純粋な上下移動では水平の向きを変えない。

`stop_on_block` は移動途中を0.25block以下の間隔で調べ、足元・膝・頭の点を固体へ入れない。これは身体全幅の厳密な衝突判定ではない。斜め下降では床に届く前にそのstepを止めるため、重力込みの着地点を実機で確認する。速い1tick移動でも途中の壁を飛び越えないことを確認する。

### 方向付きダッシュの実測（round12）

実クライアントを用いた専用フィクスチャで確認。`build/limbus-review/round12/direction-branches.json` と `path/positions.txt`、`path/sheet.png` が生データ。これは原作一致の証明ではなく、変身後S2/S3の移動を構成するための実行系の検証である。

- 後退・反転なし: X300.5→294.5、yaw=-90/pitch=15を保持。
- 後退・反転あり: X298の壁の手前X299.5で停止、yaw=90/pitch=15。
- 1tickで6block後退: 中間の壁を飛び越えずX300.5で停止。
- 前進→反転→斜め上昇→斜め下降の連続撮影: 修正前は足Y99.533が床へ侵入。足元と経路の検査後は下降終端Y100.188、その後の撮影で接地。終点X301.0であり、開始点X300.5への完全な復帰ではない。重力と床停止を含む移動距離は各技能で再調整する。

既存9武器の代表ダッシュを `round12/regression/` に撮影。Couture/Edict/Vendetta/Ember/Gossamer/Orrery/Sigil/VerdictのS1とBastion S3で、前進・通過または相手手前への接近を画像で確認した。全9件で標的HPの減少を確認。カメラ応答遅れ2〜10ms、同一フレーム連続最大1〜4。撮影完了は全技能の回帰確認や原作一致を意味しない。結果は `tools/skill-compare/data/runtime-review/dash-direction-round12.json` に保存。

### 遅延開始するVFXの時刻

`start_tick` は技のVFX開始からの時刻、各 `keyframes[].t` はその要素の開始からの相対時刻。後半に出す要素へ技全体の時刻を重ねて入れると、最初の姿勢やゼロスケールを長く保持してしまう。変身後S2の初回撮影で実際に発生し、後半の爪と白い斬線を修正した。`VfxTimeline.ANIMATION_DELAY_TICKS=1` の描画準備待ちもあるため、定義の時刻だけで接触と一致したとは判断せず、撮影フレームで確認する。

`follow: true` はアンカー位置を追うが、向きは発動時のまま。途中で術者が反転する技は、反転後に次のVFXを新規発動して方向を取り直す。単一VFXの後半要素へ分けただけでは反転しない。変身後S2の2回目の撮影で確認した。

変身後S2では37tickの反転と37tickのVFXを組み合わせると逆側へ出たため、以前は38tick発動で回避していた。round20で、PaperSchedulerの優先度キューが実行tickだけを比較し、同tickの順序を保証していないことを確認した。同tickはキューへの投入順で実行するよう修正し、S2のVFXを37tickへ戻し、実機2撮影で標的側へ出ることを確認した（カメラ遅れ最大4ms/8ms）。繰り返し処理は次回分を再投入した時点の順番になるため、常に「移動がVFX追従より先」とは限らない。この修正だけで身体と爪の連続追従が解決したとは扱わない。初期キーフレームを攻撃形状にすれば、余分な構え時間を削減できる。

固定向きのquadは反対側からの可視性を実機確認する。変身後S2では片面の平面が前半だけ消えたため、向きを反転した裏面を追加した。VFXの上限は同時Display数だけでなく、定義全体の要素slot数（256）もある。両面＋縁の平面を増やした396/288要素の定義はcontentLintが通ってもサーバー読込で拒否された。reloadの成功応答とサーバーログを確認してから撮影を開始し、拒否時に残った旧版を新版として撮影しない。

### 高速移動時のVFX追従

`follow: true` は位置追従を意味するが、従来のDisplay位置補間で高速ダッシュ中に腕の根元が身体から遅れて見える場合がある。`follow_teleport_ticks: 0` をVFX定義へ指定すると、そのVFXの追従テレポートを即時更新する。指定省略時は従来の挙動を保つ（通常Displayは2tick、位置を自分で更新するquad/spriteは更新区間の長さ）。範囲は0〜59で検証する。これはアンカー位置の補間であり、キーフレームの形の補間や向きの追従を変更する設定ではない。高速移動と通常移動の両方を撮影し、遅延だけでなく跳びやちらつきも確認する。

S3の実機比較では0tickにすると、他プレイヤーの身体表示より爪が先行する区間もあった。最終試作は3tickだが、反転直後の接続は未解決。サーバー座標への即時追従と、クライアントで補間される身体への視覚的な接続を同一視しない。


### VFXの開始tickと追従更新（round21）

共有VFX tickerが既に動いている間に遅延発動した効果は、同tickのtickerより先に登録されることがある。`play()`で年齢0を処理した後にそのtickでもう一度年齢を進めると、最初のキーフレームと寿命が1tick早まる。開始tickを記録し、そのtickでは共有tickerで年齢を進めない。

既存の同tick予約済みダッシュより先に追従位置を読む問題は、FIFO順序が保証されたPaperSchedulerで、VFX更新を遅延0の処理としてキュー末尾へ置くことで調整する。これは既にキューにある移動処理を先に実行するための変更であり、クライアント側の身体・Displayの補間まで同期したという意味ではない。発動時の向きは従来どおり固定で、反転時の古い爪の寿命は別途調整する。


空中反転の旧方向VFXを長く残すと、身体との隙間は一部隠れても両方向の腕が同時に出る。round22では上昇25〜31tick、下降31〜37tickの6tick区間へ揃え、旧方向の爪の延長と消失キーを外した。実機2撮影で重複が消えたが、新規表示の先行と身体側の隙間は残る。これは旧方向の形状を正しい期間で止めた変更であり、視覚的接続の合格ではない。

### 新規Displayと身体のずれを調べる（round23）

爪の寿命・位置補間を調整しても新規出現時の隙間が残ったため、専用サーバーの一時プローブで身体への乗客としての取付を検証した。青は移動前から取付、赤は毎tickの独立座標更新（補間0）、緑は反転tickに新規取付。上昇・下降の各5tick移動で、青・緑は描画中の身体付近を保ち、赤は身体より先行した。通常の`Player.teleport`でも10回の移動成功と取付維持をログで確認。立ち姿勢の取付点は足から約1.8blockだったが、この数値を姿勢・エンティティ共通の定数として使わない。

[PaperのAPI説明](https://jd.papermc.io/paper/1.21.11/io/papermc/paper/entity/TeleportFlag.EntityState.html)では、乗客維持は現在の通常teleportの挙動で、古いRETAIN_PASSENGERSフラグは非推奨。プロジェクトの26.3 APIでも非推奨を確認し、最終プローブではそのフラグを使っていない。

初期プローブ3本は開始時のカメラ応答105/132/108msで除外。最終プローブは3msで基準内。同一画像5枚は移動前のframe_007〜011で、移動中のframe_018/020/022は別画像だった。検証ソース・映像・ログは `build/limbus-review/round23/`、記録は `tools/skill-compare/data/runtime-review/attachment-probe-round23.json`。これは小さい印による方式の検証であり、武器の再現合格ではない。新規出現直後の位置、しゃがみ、複数表示、切断・別world移動、実際の爪を適用した場合を検証してから利用する。一時プラグインは撮影後に撤去する。

### 身体に取り付けるVFX（round24）

`follow: true` と `attach_to_anchor: true` を指定したVFXは、プレイヤーをアンカーとするfixed表示を身体へ取り付けられる。省略時は従来方式。プレイヤー以外・位置ターゲット・billboard表示・取付に失敗した表示は従来の追従へ戻る。向きは引き続き発動時のものなので、反転時は別のVFXへ切り替える。

通常のアンカー移動をDisplayの独立テレポートで追わず、身体側の描画位置へ追従する。quadの中心位置は取付先からのオフセットに変換し、形状・回転・相対位置のキーフレーム補間は維持する。足基準の配置を保つため現在のプレイヤーの高さを補正する。`follow_teleport_ticks` は取付済み表示の身体移動を補間する設定ではない。

S3の空中用VFXで適用し、frame_038/040の根元が身体付近に留まることを実機確認。取付数は同版の発動中に78、2.3秒・7.5秒後には0。初回撮影はカメラ遅れ166msで除外、再撮影は11ms・同一フレーム最大1。着地切替で素手に見える区間、人物姿勢、爪の輪郭と光帯は未一致。しゃがみ・切断・world変更・外部からの取付解除は今回の実機合格範囲に含めない。実装は取付先が無効・別world・取付解除となった表示を次の更新で削除し、寿命終了でも取付記録を消す。

### 着地時の反転と取付表示の初出（round25）

`dash_strike` の `distance: 0` と `face_movement: true` は、水平成分を持つ `local_direction` の方向へその場で向き直る。位置・pitchは保ち、移動経路の命中処理と無敵付与は実行しない。`face_movement: false` の距離0は従来どおり何もしない。次の相対ダッシュは変更後の向きが基準になるため、往復方向を再確認する。S3では37tickに反転し、46tickの突進を相対前方へ変えて左への移動を保った。実サーバーの一時定義で位置[300.5,100,300.5]を保ち、yaw−90→90、pitch15維持を確認した。一時定義は検証後に撤去。

取付表示は、通常表示として足元へ生成してから高さを補正すると、クライアントの初出フレームで下へ落ちる場合がある。生成後の座標変更だけでは残ったため、取付用の生成経路で最初から乗客の原点位置と高さ補正済みtransformを設定する。取付に失敗した場合は生成した表示を除去し、通常追従へ戻す。最終S3撮影のframe_043〜048で、空中から着地爪への切替を確認。初出時の足元への落ち込みはこの撮影では見られなかった。姿勢や別のアンカー条件すべてへの一般化はしない。

### 同じ身体の表示を次の形へ置き換える（round26）

`follow: true` のVFXへ `replace_group: edict-procuration-arms` のような識別子を指定すると、同じエンティティ・同じworld・同じグループの既存VFXを置き換える。識別子は小文字kebab-case、64文字以内。別プレイヤーや別グループ、グループ指定のない命中・花弁VFXは対象にしない。新しいプランが表示上限に収まることを確認してから旧表示を除去する。位置アンカーやfollowを無効にした再生では置換しない。

S3では囲い→地上通過→空中→着地→地上通過→遠隔攻撃中の腕を同じグループにした。地上通過の発動は13/17/20/46tickへ揃え、黒い爪を次の形への切替まで保つ。独立したフェードアウトで穴を作ったり、旧方向の爪を長く重ねたりしない構成にする。

2プレイヤーの実機プローブでは両者に青い取付表示を作り、一方だけを緑へ置換。取付数は各1を保ち、もう一方は青のままだった。現行サーバーのBlockDisplay NBTでは`block_state`を直接読んで種類を確認する。一時の定義・scoreboardは終了後に撤去。

ただし、サーバー上で旧表示を除去して新しく生成する方式には、クライアントで短い空白が残る。S3のframe_026では爪が見えず、027で次の爪が出ている。frame_024では初出直後の根元が身体より先行する箇所もある。置換グループの成功と、描画の連続性を同じ合格条件にしない。次は既存の取付表示を再利用する方法を検証する。

### Round 27 — attached Display の再利用

- 同一アンカー・ワールド・置換グループに属し、種類と表示設定が一致する生存中の passenger を次の段階へ引き継ぐ。色・文字・位置・変形を初期状態へ戻し、新しい効果の寿命で管理する。互換性のない要素は従来どおり生成する。
- 専用サーバーの2プレイヤー検証で Caster の UUID を維持したまま赤から初期色の青へ戻り、Watcher の UUID と赤色が維持された。新しい20tickの寿命終了後、Caster の表示が消えることも確認。証拠は `build/limbus-review/round27/reuse-probe.json`。
- `:plugin:core:shadowJar :plugin:combat:shadowJar contentLint` 成功。初回撮影はカメラ134ms遅延で無効、再撮影は8ms・連続重複最大2フレーム。`repeat-valid` の022〜028で爪が継続し、前回空白だった026にも存在する。
- 完全一致は未達。参照相対27/30/32/35/38/41フレームでは太い曲線状の白い光帯と青い縁、低い人物姿勢があり、現状の細い直線と角張った黒い爪とは異なる。カメラ追従、変身の状態保持と通常入力も未一致。次はこの形状・姿勢差を修正する。

### Round 28 — 横移動・空中移動の白青の光帯

- 参照相対27/30/32/35/38/41を再観察し、先端が細い白い中心と青い縁を持つ浅い曲線を既存 item-art の painter に追加。`procuration-ribbon` の生成元・PNG・フォント・sprite metadata を保存した。
- crossing / airborne の分割quad30個を両面sprite6個へ置換し、各VFXは黒い爪48個を含め78→54表示となった。60表示以内という確認はこの2定義単独についてのみで、スキル全体の同時表示上限は未認定。
- contentLint 458件成功。専用サーバーで sprite/VFX/pack を再読込し、配信ZIPのSHA1と実クライアントで新素材を確認。撮影とruntime/packハッシュは `runtime-review/ribbons-round28.json`、画像は `build/limbus-review/round28/`。
- 実撮影024で曲線と青い縁を確認し、021〜028の反転でも表示は継続した。031〜034の空中光帯はまだ水平に寄りすぎる。参照の扇状の広がり・高速な形状変化、人物の低い姿勢、黒い爪の丸み、カメラは未一致。完全一致にはしていない。

### Round 29 — 空中の移動方向と攻撃方向を分離

- 参照相対36〜44では人物が左上へ移動する一方、手元の光帯は右下へ向く。46〜50では右下への突進と大きなズームがある。同一の水平VFXでは両方を再現できないため、25tickのairborneと31tickのdiveへ分離した。
- 既存group_keyframesで手元の高さ1.2を軸に回転。上昇はlocal yaw180/pitch30、下降はyaw0/pitch35。これらは目視の調整値で、元作品の3D角度を測定した値ではない。54表示/定義を維持。
- contentLint 459件成功後、専用サーバーへ配置・reload。実撮影031〜036で左上への移動中に右下への攻撃方向、037〜040で右下への移動と攻撃方向を確認。記録は `runtime-review/aerial-direction-round29.json` と `build/limbus-review/round29/`。
- 完全一致は未達。下降時のカメラズームがまだ不足し、光帯の扇状の広がり、人物姿勢、黒い爪の曲線、持続変身と通常入力も未一致。次は参照相対46〜50のカメラ倍率・注視位置を合わせる。

### Round 30 — 下降時の比較撮影カメラ

- 参照相対44〜60を再観察。46〜50で接近、54付近で引くため、固定FOV70で30〜36tickのdollyを追加した。32〜34tickは奥行2.5、36tickで6.75へ戻す。
- 初回撮影039〜041で人物の頭部が切れたため、34tickの注視位置を `[4.5,1.6,0]` から `[3,2.3,0]` に改修。再撮影040では頭部が画面内に戻ることを確認した。初回・改修後のカメラハッシュと各2撮影は `runtime-review/dive-camera-round30.json` / `build/limbus-review/round30/` に分けて保存。
- これは比較撮影のWatcherカメラのみ。通常プレイ中のカメラ演出の実装・一致を証明するものではない。画角自体も完全一致未認定で、参照の白い光帯が覆う面積、黒い爪の比率、人物姿勢の差が残る。
- 次は下降用VFXの白い扇の広がりと黒い爪の比率を参照に合わせて修正する。全10武器の完了条件は維持し、この局所修正で完了とはしない。

### Round 31 — 下降時の白い扇と黒い爪の比率

- dive の光帯を両面3本から5本へ増やし、太さ・長さ・開きを0〜4tickで変化させた。黒い枝の断面を55%へ縮小。表示は58個/定義で、全スキル同時表示の認定ではない。
- contentLint 459件成功。round30のカメラを固定して2撮影。初回は000〜007が同一でcast300msをまたぐため、通常の粗い閾値を通っていても無効に訂正。再撮影は連続重複最大1、カメラ遅延2msで、040の白い面積増加と黒い枝の縮小を確認した。
- `runtime-review/dive-fan-round31.json` と `build/limbus-review/round31/` に初回と採用映像を保存。完全一致は未達。拡大された128px素材の境界が階段状で、参照の滑らかな光帯・残光・人物姿勢に届かない。次は素材解像度と重なりを確認し、物理サイズを保って改善する。

### Round 32 — 光帯素材の解像度

- procuration-ribbon を既存painterから128→256pxへ再生成。crossing/airborne/diveの22個の両面spriteについてx/y scaleを半分にし、素材寸法×scaleを維持する数値検証を行った。黒い爪の設定は変更していない。
- item-art生成、resource-pack生成、contentLint459件成功。配信ZIPのSHA1を確認してreload。採用再撮影は連続重複最大1・カメラ遅延10ms。024/038/040で光帯の表示と拡大時の段差縮小を確認した。
- 初回の保存は次の出力置換と競合してmetadataがない不完全コピーになったため、`partial-archive-not-for-review` として除外。証拠は完成後に保存した `round32/repeat` だけを採用し、`runtime-review/ribbon-resolution-round32.json` に明記した。
- 完全一致は未達。比較は人物の描画位相が同一ではなくpixel一致を示さない。光帯の重なり・参照の残光、人物姿勢・通常プレイのカメラ・持続変身・通常入力は残課題。今後の撮影保存は出力置換が始まる前の同一プロセス内で行い、不完全コピーを避ける。

### Round 33 — 上昇後に標的へ残る斜線

- 参照相対42では人物が左上へ離れても標的上に3本の斜線が残る。26tickの既存impactと同じ対象選択で、follow=falseのair-traceを追加。3本×両面の6表示が0〜4tickで縮み、5tickで終了する。既存mechanic列が不変で追加はplay_vfxのみであることを確認した。
- contentLint460件成功。実撮影032〜034では標的に残る線と移動する人物が分離し、035〜036では残光が見えなくなる。2撮影のカメラ遅延は4ms。記録は `runtime-review/air-trace-round33.json` / `build/limbus-review/round33/`。
- 今回は `ARCHIVE_DIR` を指定し、撮影batch自身が次の撮影を始める前に保存した。両撮影にmetadataがあり、途中コピーの混在を回避できた。
- 完全一致は未達。透明度の減衰を幅縮小で近似しているほか、対象選択・命中時刻も元作品の内部仕様を証明したものではない。人物姿勢・通常プレイのカメラ・持続変身・通常入力の残課題を維持する。

### Round 34 — sprite の透明度キーを実表示へ接続

- sprite のkeyframe `alpha` を初期TextDisplay opacityと更新へ渡す。quadと同じ補間を再利用し、spriteの既存色は保持する。色キーは引き続きquad専用。初期opacityも生成前に設定し、0近傍が不透明として扱われる仕様を避けるため最小4へclampする。
- 初回の実機probeでdomain validationがsprite alphaを拒否する経路を発見し修正。schemaのcontentLint成功だけで実読み込みを認定しない。再起動後VFX180件・失敗0を確認し、probeで64→191→4、寿命終了後の消去を確認。fixtureは削除・reload済み。
- core/combat shadowJarとcontentLint成功。残光の幅は保ち、長さの縮小にalpha減衰を加えた。他の既存spriteにalphaキーがないことを確認。初回撮影はカメラ114ms遅延で無効、再撮影は9ms・重複最大1を採用した。
- 記録は `runtime-review/sprite-opacity-round34.json` / `build/limbus-review/round34/`。033〜036で標的上の残光が薄くなって消えるが、身体側の爪と重なるため独立した画素測定による減衰一致は未認定。全10武器の形状・動作・命中・カメラ等の完了条件は維持する。

### Round 35 — 撮影更新中も再生中の版を固定する

- 比較画面のMC URLは版番号を持っていたが、サーバーは各フレームを可変の撮影出力ディレクトリから取得していた。撮り直し中にページのcount/metadataと画像がずれる経路を修正した。
- `tools/skill-compare/scripts/publish-capture.mjs` で完成・レビュー・ハッシュ記録済みの撮影を公開する。連番PNGの欠落、空フレーム、無効capture、skill/view不一致を拒否し、完成版をSHA256の固定ディレクトリへ保存してcurrent.jsonをatomicに置換する。途中の書き出し先を公開しない。
- 公開先はサーバー側 `/IdeaProjects/Astera/build/skill-compare-published`。新しい撮影を採用する際はREADMEの公開コマンドを必ず実行する。開いているページは旧版を使い続け、新しく開いたページだけ新版へ移る。reference/contentの一致判定は維持する。既に開いていた旧方式のページは一度再読込が必要。
- 手動HTTP検証で、元画像・meta消去中にも旧版が取得できること、不完全/invalid公開ではcurrentが変わらないこと、新版公開後も旧版が取得できることを確認。現在参照可能だった105撮影ビュー（105スキルではない）を公開し、認証済み公開URLで版固定・S3終盤再生・ループ復帰・frame HTTP errorなしを確認した。
- 記録は `runtime-review/publication-round35.json`。これは再生の安定性改善で、全10武器の完全一致の認定ではない。利用者側のアクセスエラーの原因はまだ未確定であり、Access認証設定は変更していない。

### Round 36: additional Crimson reference and thumbnail consistency

- Added `crimson-chain-axe-transformed-combo-a`: source Gpj-lvOnAgs, native frames 2342–2605 (exclusive), 263 frames at 30000/1001 fps. The comparison row is explicitly observed / implementation missing, with an unresolved skill name and coin count.
- Reviewed the source contact sheet (`build/limbus-review/round36/crimson-combo.jpg`): claw introduction, low and diagonal chain sweeps, vertical loop, descending arc, then airborne crossed ellipses and floor glows. Five visual contact candidates are markers, not proof of five coins or internal damage events. Do not substitute the later column finisher or base-form captures.
- Remaining Crimson extra records describe a transformation and ally/support preparation; do not count them as two additional attack skills without further evidence.
- Index Minecraft thumbnails now use the same published revision and image extension as the comparison player. All 54 available thumbnail URLs returned HTTP 200 after deployment. This fixes capture consistency, not Cloudflare login permissions or reproduction fidelity.

### Round 37: Crimson transformed combo A first prototype

- Added a directly castable sequence with four chain swing prototypes and provisional contacts at ticks 43/49/65/82/116; no base input binding was changed. Contact amounts/ranges are implementation assumptions, not observed game statistics.
- `contentLint` passed; dedicated runtime reload reported VFX 183/0 failed and skills 79/0 failed. Actual-client capture: 195 frames, maximum camera lateness 3 ms, maximum identical run 1. Evidence: `build/limbus-review/round37/captures`.
- Visual review failed: intended horizontal/vertical rig planes are reversed on screen, physical chain is too thin and straight, petals obscure the attack, orbit remains a segmented polygon, caster never floats. Body/claw pose, floor flares, form persistence and normal input routing remain absent. Correct rig axes and reduce petal emission next, then re-film; do not accept this capture as matching.

### Round 38: Crimson swing axes and petal density

- Read `VfxRigMotion.exact`: rotation is tilt × swing × rest; yaw motion with pitch-90 rest traces a horizontal plane. Corrected low/diagonal/loop/descending outer rolls to 0/-45/-90/-55 degrees. Removed repeated rig particle emitters while retaining the short display petal burst; other Crimson skills are unchanged.
- `contentLint` passed. Re-filmed with unchanged camera: 195 frames, maximum camera lateness 3 ms, longest identical-frame run 1. Actual sheet confirms low horizontal first sweep and vertical third sweep; the previous petal cloud is gone.
- Fidelity still fails: segmented red trail bars, insufficient flexible chain curvature and crescent link geometry, stationary caster, missing claw/body pose, floor glows and form/input routing. Evidence: `build/limbus-review/round38/captures`. The existing freeze/hold mechanics only apply slowness/jump suppression (and one velocity reset); they do not establish an airborne hold and must not be mistaken for hover support.

### Round 39: Crimson trail continuity

- Root cause of separated red bars: `VfxRigPlanner.slab(shrink=false)` shortens each opaque band's chord during fade. More samples alone cannot remove the growing gaps.
- For the four transformed-combo rigs only, switched the red band to existing `soft` quads, whose transform remains fixed while opacity fades. White outer edge remains a thin slab that shrinks in thickness rather than chord length. Reduced angular sampling step from 24 to 18 degrees. No shared renderer behavior was changed.
- `contentLint` passed; actual-client comparison is required before accepting visual improvement. Floating/body pose, chain geometry, floor effects, transformation and input routing remain unfinished.
- Actual capture completed: 195 frames, 3 ms maximum camera lateness, maximum repeated-frame run 2. Review **failed**: horizontal red arc is continuous, but tilted/vertical red surfaces are nearly absent while white edges remain. Suspect back-face visibility of the soft quad; inspect orientation and provide two-sided rendering before calling this an improvement across all four swings. Keep this failed capture as diagnostic evidence, not an accepted replacement.

### Round 40: Crimson trail back faces

- Added an opposite-facing soft trail by reversing its radial endpoints. Cross product in `VfxRigPlanner.surface` reverses the face normal; omitted the duplicate edge. No renderer/API changes were needed.
- `contentLint` passed; runtime VFX reload succeeded. Actual-client capture: 195 frames, camera lateness maximum 3 ms, repeated-frame run maximum 2. `frame_055.png` shows the previously missing tilted red surface, now a continuous translucent arc. This supports the back-face diagnosis from round 39.
- Still failed: polygonal corners, weak white glow, chain shape and caster motion/pose, floor effects, transformation/input routing. The additional faces increase display usage; complete-cast live display budget remains unverified and must be measured, including overlapping swings and impact effects. Capture: `build/limbus-review/round40/captures`.

### Round 41: complete Crimson cast live-display measurement

- Ran the actual direct combo cast with RCON sampling of block/text/item displays within 20 blocks of the scene: 757 samples, initial count zero, observed combined peak 133. Queries for the three types are sequential, so the sum is not an atomic tick snapshot; nevertheless **one block-display query returned 104**, independently proving the handoff limit of 60 is exceeded.
- Capture completed: 195 frames, maximum camera lateness 2 ms, repeated-frame maximum 3. Raw timestamped command replies: `build/limbus-review/round41/live-displays.json`; summary: `data/runtime-review/crimson-display-budget-round41.json`.
- Do not use successful runtime admission (global engine budget 600) as proof of the per-cast 60 limit. Rework overlapping physical rigs and impact counts while retaining two-sided trails, then repeat full-cast measurement and visual comparison. No fidelity pass.

### Round 42: reduce overlapping Crimson displays

- Replaced previous physical-chain group when the next swing starts. Kept ten links, combining each pair of white edge bars into one spanning bar (30→20 parts). Both soft trail faces remain. Replaced display petals with two short particle emissions. Added a dedicated five-element, five-tick contact flash; base Crimson skills are unchanged.
- Initial runtime reload rejected a zero-length particle emitter interval despite schema lint passing; corrected to ticks 6..7 and successfully reloaded **before** capture began. Final contentLint passed.
- Actual capture: 195 frames, camera lateness 5 ms, identical-frame maximum 1. Frame 055 confirms chain and red arc. Actual sequential sampling: 756 observations, peak 61 (36 block + 25 text), down from 133, but the 60 cap is not proven. Next reduce duplicate contact-ray geometry without losing the four-point flash. Original shape, glow, pose, float and form/input routing still fail.
- Evidence: `build/limbus-review/round42/{captures,live-displays.json}` and `data/runtime-review/crimson-display-budget-round42.json`.

### Round 43: combine contact flash rays

- Combined opposite contact rays into two perpendicular full-length rays, retaining the core: five→three displays, horizontal extents -1.2..1.3 and vertical extents -0.25..2.65 in the intended local geometry. Base skills remain unchanged. ContentLint and runtime reload passed.
- Actual one-cast sampling: 763 observations, maximum 59 (34 block + 25 text), compared with 61 previously. This is sampled evidence, not an atomic per-tick upper-bound proof or fidelity certification. Frame052 shows the four-arm flash.
- First recording has 12 identical frames175..186 crossing the orbit's expiry; marked invalid despite the automatic threshold not triggering. Re-record the same content before publishing. Independent runtime-count evidence remains usable: `build/limbus-review/round43/live-displays.json`.
- Repeat recording completed with the same content: 195 frames, maximum repeated run 2, camera lateness 2 ms. Rechecked frame052 four-arm flash. Publish the repeat only; preserve the first invalid recording in the archive. Fidelity remains failed.

### Round 44: Crimson ascent and hover prototype

- Source observation: rise around tick103, float through the clip ending at176; no source descent was established. Added a 3.6-block upward dash over12 ticks, then cast-origin-relative teleport/zero-velocity every tick115..175. Existing mechanics suffice for the clear-arena prototype; no new runtime API. End-of-duration release is cleanup, not an asserted original landing animation.
- ContentLint and runtime skill reload passed. Recorded293 actual position samples: Y100→103.6, sampled interval6.1..8.6 seconds remains exactly103.6, eventually returns to100 after release. Capture195 frames, camera lateness3ms, repeated-frame maximum1. Evidence `build/limbus-review/round44/positions.json`, captures and `data/runtime-review/crimson-hover-round44.json`.
- Frame140 confirms airborne caster but upper orbit clips the screen. Failed review: fix orbit geometry/placement and comparison framing next, retain original ground target context. Pose, floor flare, chain blade geometry and form/input routing remain absent. Clear-arena success does not validate obstruction or interrupted-cast behavior; snapshot teleport must be reviewed before normal gameplay routing.

### Round 45: crossed hover ellipses and framing

- Replaced the borrowed three-ring block effect with a dedicated two-ellipse prototype: 12 segments per ellipse, both faces, 48 quads total; 40-tick duration begins at116 and fades after149. Static shape is provisional; original orbit dynamics and glow remain unmatched. Moved the comparison camera at116 to include the elevated caster, complete ellipses and ground target; this is not a gameplay-camera implementation.
- First capture exposed omitted `billboard: fixed` (quad defaults to center): segments faced the camera independently. Added fixed orientation and re-filmed. Final195-frame recording has maximum repeat1 and camera lateness2ms; frame140 confirms continuous crossed ellipses without top clipping.
- ContentLint and runtime reload passed (185 VFX/0 failed,79 skills/0 failed). Final actual-cast count762 samples peaks at59; sequential sampling remains limited evidence, not a full upper-bound proof. Evidence `build/limbus-review/round45/{captures,live-displays-repeat.json}`.
- Fidelity still failed: thin polygonal red lines instead of bright curved ribbons; missing floor flares, original body/chain shapes, transformation and normal input routing.

### Round 46: smooth white-core hover ribbons

- Added deterministic `crimson_orbit` painter and256px sprite using the existing item-art pipeline, with white core/red rim. Regenerated only this sprite/font/metadata. Replaced48 polygon quads with4 fixed sprite faces (two crossed ellipses, both sides), preserving fade timing and comparison framing.
- Item-art generation, resource-pack build and contentLint passed. Dedicated pack reloaded SHA1 `245cb2747aab6317c25984936082365b43200fc9`; sprite and VFX runtime reloads succeeded.
- Actual195-frame recording: maximum repeated run1, camera lateness2ms. Frame140 confirms smooth ellipses with bright cores. One-cast entity sampling768 observations peaks at59; ellipse stage uses4 planned displays. Evidence `build/limbus-review/round46/{captures,live-displays.json,astera.zip}`.
- Still failed against original: static uniform rings instead of evolving streaks, floor glow absent, body/claw/chain shapes and form/input routing unfinished. Smooth sprite edges are partial progress, not a matching certification.

### Round 47: ground-target flare

- Source extra3 describes2–3 floor flares with thin rings from83.951–85.6s. Added a dedicated fixed-world six-display flare (two-sided red/cream star plus expanding ring), starting116 for34 ticks, using the cast-origin ground cone to select up to3 targets. Added visual effects only; damage count/amount unchanged because visual pulses alone do not prove additional hits.
- ContentLint and runtime VFX/skill reloads passed. One-target actual195-frame capture, camera lateness2ms, repeated-frame maximum2. Frame140 confirms the flare on the target's feet, separate from the elevated caster. Whole-cast sampling769 observations peaks at59.
- Failed fidelity review: insufficient light intensity, only one target validated; multi-target layout remains to film. Original moving ribbons, character/claw/chain form, input routing and actual hit semantics remain unfinished. Evidence `build/limbus-review/round47/{captures,live-displays.json}` and `data/runtime-review/crimson-floor-round47.json`.

### Round 48: three ground flares and brighter cores

- Added a camera-facing red halo/cream core above each floor flare while keeping the ground ring fixed. Tested with three actual husks at world(305.5,100,300.5),(304.5,100,298.5),(306.5,100,302.5); fixture runner archived under `build/limbus-review/round48/capture-three-targets.sh`. Removed the fixture husks after capture.
- ContentLint and runtime reload passed. Actual195-frame recording: camera lateness2ms, repeated-frame maximum4. Frame140 shows three separate ground flares below the airborne caster. Sampling769 observations peaks at59. Evidence `build/limbus-review/round48/{captures,live-displays.json}`.
- This verifies three visual targets only. Damage semantics remain unverified; no extra damage events were added. White/red flare strength, evolving ribbons, body/chain form and normal transformation/input routing remain unmatched.

### Round 49: physical recovery chain remains after glow

- Re-inspected source at85.9s: paired crescent blades on a curved physical chain remain after the luminous rings weaken. Added deterministic ivory/pink `crimson_link` sprite with dark spine;14 two-sided links along an arc-length sampled cubic curve. Plays116..176; this addresses the previously absent late chain, not the still-bar-shaped early swing rigs.
- Item-art/resource-pack builds and contentLint passed. Dedicated pack and sprite/VFX/skill reloads succeeded. Actual195-frame capture: camera lateness3ms, repeated-frame maximum2. Frame168 confirms the physical chain after glow expiry. Three-target whole-cast sampling768 observations peaks at59 (limited sequential evidence).
- Failed review: recovery chain is static, hand/body attachment not reproduced, early chain rigs still use bars, character/claw/cape pose and original light/motion/input/hit behavior remain unmatched. Source frame, generator, pack, captures and counts under `build/limbus-review/round49`; summary `data/runtime-review/crimson-chain-round49.json`. Fixture husks removed after capture.

### Round 50: crescent links on the four early swings

- Replaced20 physical block parts per swing with20 sprite faces (10 links, front/back), using the same paired-crescent asset. Sampled the existing rig tilt×swing rotation at integer ticks into group tracks, with rest rotation applied to link offsets/orientation. Existing red trails remain rig-generated. Hit-stop synchronization with those independent sprite tracks is not established.
- Initial YAML shared-reference output exceeded the runtime alias limit, despite contentLint passing. Rewrote without aliases; runtime loaded187 VFX/0 failed at04:41:40, before actual cast04:41:42.261. Final lint passed. Do not bypass parser limits.
- Actual195-frame capture: camera lateness2ms, repeated-frame maximum2. Close frame072 confirms paired blades; low frame048 exposes edge-on visibility loss. Three-target whole-cast sampling769 observations peaks at59. Evidence and source generator under `build/limbus-review/round50`.
- Fidelity remains failed: edge-on readability, rigid link geometry/hand attachment, independent hit-stop tracks, character pose, original lights/hits and transformation/input routing. Removed fixture husks after capture.

### Round 51: blade orientation review

- Twisted the paired-blade planes by60/30/0/20 degrees for low/diagonal/loop/descending swings. Rejected the first raised-pivot trial because it attached at the chest; restored original pivot[-0.25,1,0.3]. No added displays or resource-pack change.
- Final contentLint and runtime reload passed. Final repeat capture195 frames: camera lateness2ms, maximum identical run1. Actual display sampling768 observations peaks at59; sequential queries are not an atomic upper-bound proof. Use round51/live-displays-repeat.json and the archive ending1792730965363773900, not the earlier trial.
- Frames044/046 and072 show paired blades more clearly. Frame048 loses the chain and050 is nearly edge-on: this is partial improvement, not solved horizontal visibility. Hand attachment, pose, independent hit-stop tracks, original motion/lights/hits and normal transformation/input routing remain failed or unverified.

### Round 52: preserve the first chain contact before replacement

- Re-observed native source frames2405/2407/2409/2412/2415 (case63/65/67/70/73). The low chain remains extended after first contact. Existing second swing started at43 and replaced the first at its own contact43. Moved second swing start to47 and compressed its forward sweep from6 to2 ticks, retaining contact49. Source frames/hashes are under round52/reference.
- ContentLint and actual VFX/skill reload passed. First actual capture confirms extended links in frames050/052, whereas round51 lost them. Sampling768 observations peaks at59. However identical frames140..147 span active fading effects; marked that capture invalid and requested a fresh capture. An automatic quality pass is not sufficient to certify motion.
- Fidelity remains failed: rigid straight chain instead of original deformation, missing body/claw/cape/hand pose, camera framing, light strength, hit semantics and normal transformation/input routing. The independent sprite tracks do not establish rig hit-stop synchronization.
- Final repeat:195 frames, camera lateness2ms, maximum identical run2. Frames050/052 retain the low chain through contact;056 shows the second sweep. Frames140/147 show changing floor fade. Repeat768 observations peaks59. Accepted capture timestamp2026-10-23T04:59:42.801498+00:00; first invalid archive retained.

### Round 53: deform the first chain sweep

- Re-observed native source2401/2403/2405/2407: physical chain bows around/above caster before extending at first contact. Replaced the low-sweep rigid link offsets with a time-varying parabolic centerline. Ten links remain two-sided; positions use equal arc distance, spine orientations follow tangents and lengths follow local spacing. This is a screen-plane approximation, not recovered3D source geometry. Other swings remain rigid.
- ContentLint and runtime VFX reload passed; deployed bytes checked. Actual195-frame capture: lateness2ms, maximum repeated run1. Frames044/048/050 show bow-to-straight transition. Sampling767 observations peaks59. Generator/source hashes/capture/counts under round53; summary data/runtime-review/crimson-chain-bend-round53.json.
- Fidelity remains failed: exact deformation, hand attachment, other swings, body/claw/cape pose, light/camera/hit semantics and normal transformation/input routing. No new pack, display count or damage changes.

### Round 54: preserve the diagonal chain bow through contact

- Source f2414/2416 shows the physical chain elevated and curved through the diagonal contact. Changed only diagonal link centerline, with bow1.8 at0/1,1.3 at2,.4 at4,0 at6 and slight residual bow later. Ten paired-face links retain tangent alignment and equal arc spacing. World depth/shape remains an approximation.
- Source loop contact f2438/2440 is heavily cape/flash-occluded; do not infer a precise curve from the luminous fan. Descending f2465..2468 has a hook behind/above caster while the luminous arch extends toward target; f2471 returns low/front. These shapes remain to implement. Source frames/hashes and generator under round54.
- ContentLint, runtime reload and deployed byte comparison passed. Actual195frames, camera lateness2ms, repeated maximum1. Frame055/056 confirms elevated/bowed chain;054 remains thin/edge-on. Sampling765 observations peaks59. Exact geometry, hand/body pose, light/camera/hits and normal form/input remain failed/unverified.

### Round 55: descending hook independent of luminous fan

- Source f2463..2471 shows physical chain behind/above caster while the red arch reaches toward target, then low/front return. Replaced descending sprite group rotation with independent anchor-local cubic centerlines at0/4/6/9/13; equal arc spacing and tangent-aligned blades. Ten links/two faces remain; original rig-generated luminous fan unchanged. Shape/depth still approximate.
- ContentLint, runtime reload and deployed bytes passed. Actual195frames, lateness2ms, max identical run1. Frames089/092 show backward/up hook and095 low forward return. Close frame086 clips the chain at screen top: framing/body pose remain unmatched. Sampling769 observations peaks59. Generator/source/capture/counts under round55.
- Additional source f2431/2434/2436 shows elevated target while caster grounded. Current target motion does not reproduce that; observe launch/landing boundaries before implementation. Do not confuse it with later caster ascent. Remaining shape/pose/light/camera/hits/form-input mismatches keep fidelity failed.

### Round 56: bind one target before airborne choreography

- Source f2428 and2431..2454 shows target elevated while caster is grounded; launch/landing remain flash-obscured. Current repeated nearest-cone selection may switch to a bystander once the selected target rises. Existing CastSkill platform wiring ignores its targeter/context target, so it cannot supply binding.
- Added for_each_target: resolves distinct living targets once per wrapper firing, dispatches children with stable target IDs, and skips children while the entity is missing/dead/in another world. Caster/origin do not change; children must explicitly use target. Updated recursive support and cycle validation plus targeted schema branches. Schema generation also exposed unrelated pre-existing drift; preserved existing unrelated schema fields.
- Core/combat JAR builds, schema generation and contentLint passed. Dedicated runtime probe with actual Caster client: moved selected A beyond cone while B remained nearer; only A lost HP(194.096), B stayed200. Cross-world and removed-target cases left B200; transferred A stayed200. First world-transfer attempt lacked a loaded destination chunk; repeat used temporary forceload and cleaned it. Probe skill removed afterward.
- Evidence/scripts/runtime hashes under round56; summary data/runtime-review/target-binding-round56.json. This verifies binding only, not Crimson airborne motion, exact damage or overall fidelity. Production skill and published comparison remain round55 until movement is implemented/captured.

### Round 57: bind contacts and move the selected target

- Wrapped the five provisional damage/impact pairs in one for_each_target selected at43; child relative delays preserve absolute43/49/65/82/116. Other enemies are not reselected during the combo. First velocity-only trial produced no height change on the NoAI target despite all five HP reductions; retained its archive as a rejected movement trial.
- Added target_origin location: captured target position/world from the enclosing wrapper, unaffected by live caster pose. Outside the wrapper it is unavailable. Used absolute offsets for ascent51..58,2-block hold58..100, descent100..107. These boundaries/height are provisional, not original-world measurements. No automatic obstacle clearance; keep as direct prototype.
- JAR builds succeeded; combined schema generation/lint initially ran lint before fresh schemas. After generation, preserved unrelated pre-existing schema fields and ran lint sequentially:471 passed. Dedicated server loaded the new definition; deployed JAR/skill hashes checked.
- Repeat actual195-frame capture: lateness8ms, identical maximum2. Positions show selected A100→102→100; HP has five reductions200→170.47997. B/C stayY100,HP200. Frames065/075/100/115 confirm rise/airborne/landing;075 also exposes chain too low for airborne body. Sampling738 observations peaks59. Evidence under round57, summary data/runtime-review/crimson-target-motion-round57.json.
- Fidelity failed: precise trajectory/height/timing, chain-to-target alignment, body/claw/cape pose, light/camera, hit semantics and normal form/input routing. Collision, active AI, player targets and interruption not validated.

### Round 58: keep the airborne target inside the close-up

- Source f2440 shows the elevated contact in-frame; round57 close frame072 cropped the target's head above the top. Changed only comparison camera key65 from pos(3,1.8,3)/target(3,1.1,0) to pos(2,2.8,4)/target(2,2,0). All skill/target-motion data remains round57.
- Actual195-frame capture: lateness4ms, identical maximum1. Close frames070/071 now contain the airborne head and grounded caster;072 has already returned wide. Do not assume an old frame number always has the same cut boundary. Sampling759 observations peaks59. Camera hash and evidence under round58.
- This improves comparison visibility, not gameplay camera or exact source framing. Body scale/pose, chain height, light and other fidelity gaps remain failed. Existing custom player-animation gateway currently accepts only crouch; arm swing has a separate gateway method and is not exposed by play_animation.

### Round 59: lower caster posture during grounded swings

- Source f2407/2440/2465 has a low/leaning caster through contacts. Applied existing crouch animation at37..47,59..69,76..103, ending before ascent. This is only a vanilla height/lean approximation; no arm extension or torso twist was added.
- ContentLint and runtime reload passed; deployed skill bytes checked. Actual195frames, lateness3ms, identical maximum1. Frames050/071/086 show crouch;115 shows normal stance during ascent. Sampling766 observations peaks59. Evidence under round59; summary data/runtime-review/crimson-crouch-round59.json.
- Fidelity failed: arms still hang downward, chain origin does not follow a matching hand pose, airborne contact/chain height remains wrong, source body/claw/cape form, light/camera/hit semantics and normal form/input remain unfinished. Do not label crouch as the original full pose.

### Round 60: native arm swing alongside the chain

- Exposed existing main-hand swing through play_animation using swing-main-hand. A positive ticks value emits one native swing; zero skips it, and duration does not retime or hold the native animation. Reuses swingArm, including its existing event. Repository search found no skill-trigger consumer of PlayerSwungArm. Unknown custom animations retain failure behavior.
- Added swings at40/46/62/79, three ticks before provisional contacts. Core JAR build/contentLint passed; dedicated core/skill deployed and hashes checked. Actual195frames, lateness8ms, repeated maximum1. Frames047/054/071/086 show arm motion; no unknown-animation/runtime-dispatch error found.
- HP after capture: selected170.47997, other two200, same as previous five-contact result; no extra damage observed. Display sampling733 observations peaks59. Evidence under round60; summary data/runtime-review/crimson-arm-swings-round60.json.
- This reduces idle arms, not full fidelity. Native arm direction/speed, torso twist, hand-to-chain binding, airborne contact alignment, source form/lights/camera/hits and normal input remain incomplete.

### Round 61: Couture follow-up reference cases

- Registered Patterning (native frames641..727) and probable Cutout (1414..1618) as missing implementations, with no asteraSkillId. Never borrow base-slot captures for an unimplemented follow-up. One reused coin in Patterning produces two visible contacts; Cutout's five falling blades are one contact cluster.
- Prepared86/204 native frames and visually checked overhead preparation and both contacts. Browser playback reached both ends and looped without errors; index exposes both as 未実装・未接続 with no Minecraft sources. Local evidence: build/limbus-review/round61.
- extra-3 depicts another ally, not Rodion; preserve as context for follow-up triggers, exclude from this weapon's attack count. Trigger logic, actual implementation/capture and fidelity review for both new cases remain outstanding.

### Round 62: Patterning direct prototype

- Added two overhead shears flights and same-target contacts21/41. Single-coin reuse remains two observed contacts, not two base coins. Passive trigger/normal input are not implemented.
- Initial runtime load rejected duplicate keys; corrected before capture. First capture exposed second-flight timing error: element keys must be relative to spawn, not whole VFX time. Corrected and recaptured; keep rejected evidence separate.
- Final77frames, camera5ms, repeated maximum1. HP200→188.192→182.288 in two steps about0.993s apart; critical damage means magnitude is not a reference match. Display sample peak43 across769 sequential observations.
- Fidelity failed: fixed4m flight does not track arbitrary targets; linked trail/pose/model detail/light/camera and exact arrival versus damage remain incomplete. VfxTimeline also starts interpolation1tick after element spawn; account for this in timing review. Evidence build/limbus-review/round62, runtime-review/couture-patterning-round62.json.

### Round 63: Patterning linked trail and interpolation timing

- Inspected sourcef677/707; added8 overlapping outline links with two faces, and moved flight-arrival keys1tick earlier to account for interpolation delay. First capture had gaps and overlong second trail; widened link overlap and ended second trail at51, then recaptured.
- Final77frames; camera5ms, identical maximum2 (036/037 only). Sequential display sample771 observations peaks59. HP200→194.096→188.19199, two drops about1.006s apart. No damage changes.
- Failed fidelity persists: fixed4m static geometry, pose/model detail, original braid/glow, thin horizontal impact rays, source camera and passive routing are not matched. Same-time images at /evidence/couture-patterning-tail.png; runtime-review/couture-patterning-round63.json.

### Round 64: Patterning impact shape

- Replaced shared impact only in Patterning with a dedicated glint-based horizontal pair, brief vertical/core pulses, and six emitted petals. First capture was too thin/occluded; increased thickness/core and enabled sprite see_through, then recaptured. Occlusion through walls is not verified.
- Final77frames, camera6ms, repeated maximum2 (019/020 held preparation). Display sample773 observations peaks55; HP200→188.192→182.288, two drops1.003s apart. Damage mechanics unchanged.
- Fidelity failed: enlarged32px glint has hard edges; vertical punch/glow/petals, target-relative flight, original pose/camera and passive routing remain incomplete. Evidence /evidence/couture-patterning-impact.png and runtime-review/couture-patterning-round64.json.

### Round 65: live target endpoint and shared cancellation

- Added opt-in bound-target VFX correction. Element target weights use the same spawn+1+t clock as transforms; translation preserves model rotation/scale. Fixed-anchor effects only; no emitting elements. Patterning selects the target once before starting its choreography.
- Bound children and tracking VFX share cancellation. On observed death/disappearance/world mismatch the effect ends and delayed children stay cancelled after return. Actual probes: remove/world-change/world-return, display28→0; world-return HP200 after delayed-hit times.
- Real clients: target moved4→6m forward,+1m lateral,+1m up between contacts. Final moving77frames,camera9ms,maxrepeat2,HP200→194.096→182.288. Final static77frames,camera6ms,maxrepeat1,HP200→194.096→188.19199; static display peak55 from761 sequential observations.
- Cold first casts delayed camera150/257ms; reject those captures. After a rehearsal, recreate scene/target and record moving/static separately. Publish static source-comparison only. Build core/combat and contentLint474 passed; schema edits limited to new fields/definition.
- Fidelity remains failed: translation does not align blade/trail rotation or stretch links, so long-distance gaps remain. Pose/light/camera/trigger semantics and collision/occlusion remain unfinished. Evidence /evidence/couture-patterning-tracking.png; runtime-review/couture-patterning-round65.json.

### round66：Cutoutの直接呼出し試作

5本の鋏を頭上で展開し、同一標的へ下降させ、巨大鋏の閉鎖へ移る。接触は67/115tick、実機HPは200→194.096→188.19199。156フレーム、カメラ遅延最大9ms、連続重複最大1、Display観測最大45（752サンプル、逐次問合せ）。

5本の鋏→下降接触→巨大鋏閉鎖の直接呼出し試作。花飾り・白い連結軌跡・接触時の発光・腕姿勢・カメラは未一致。追撃条件は未接続。 名前の対応も推定のまま。撮影済みは再現合格を意味しない。詳細: `runtime-review/couture-cutout-round66.json`。
