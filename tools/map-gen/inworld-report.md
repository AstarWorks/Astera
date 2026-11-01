# anni-inworld — real-client evidence (2026-11-01)

厳密なゼロ差分条件は未達。実クライアント撮影は成功し、旧 frost 残骸は検出されなかった。
新規クリーンスロットでの検証なので、旧 fixture を入れた sweep 再現を証明するものではない。

## 実行と成果物

- ローカル独立 Paper 26.3 + Minecraft 26.3 / Xvfb / llvmpipe、1920x1080。
  VM と live dev コンテナにはアクセスしていない。新規 slot 7、原点 `(14336,0)`。
- `annihilation-arena` 1,416,561 blocks。開始→Paper ready 82 s、stage Ready 107 s。
  plan 14:03:31 UTC→Ready 14:04:02 UTC、31 s。撮影・停止・保存まで203 s。
  Ready は既存の clear→paste→verify の通過後にのみ公開される。
- `/tmp/claude-1000/anni-map/inworld/after/founder-view.png`
- `/tmp/claude-1000/anni-map/inworld/after/eye-level.png`
- `/tmp/claude-1000/anni-map/inworld/after/top-down.png`
- `/tmp/claude-1000/anni-map/inworld/after/underside-low.png`
- `/tmp/claude-1000/anni-map/inworld/compare.png` (before | after、3840x1080)
- 元画像の原本コピー: `/tmp/claude-1000/anni-map/inworld/before/founder-view.png`。
  比較画像のみ before を1920x1080へリサイズ。after は実クライアントの無加工画像。
- 領域: `world/dimensions/minecraft/overworld/region`。停止後の同一ワールドを保存。
  `server.log`、`phases.log`、`cameras.log`、`placement.txt`、`world/` も保存。
- 終了 trap がクライアントと専用コンテナ・volume を撤去。
  `ASTERA_HEAVY_HOST= SHOT_ID=c80a8587 make vfx-shot-down` も実行済み (`down.log`)。

## カメラ導出

元画像に F3/座標/FOV のメタデータはないため推定。赤い町が左下、黄の町が上、
中央の24×24の ruin court を Highmarch の西 outpost `(-85,-50)` と照合した。
court の4角と旧 frost 西島中心 `(-177,64,-101)` を既知座標として透視投影を
least-squares fit。FOV70固定時、目位置 `(-124.2567,149.2435,-121.7852)`、
feet y=147.6235、yaw=-0.9233、pitch=62.2080、画素RMS 8.3935。
実コマンドでは awk の丸めにより x=-124.3 (world x=14211.7)。
元 dev slot 2なら推定 feet は `(3971.7433,147.6235,-121.7852)`。
原点を除けば同じ構図だが、カメラ完全一致とは主張しない。
導出全体と計算コードは `derive-camera.md` / `fit-camera.py`。

他カメラ (local feet x/y/z, yaw, pitch):

- eye-level: `(0.5,65,-99.5), 0,0`。赤拠点の T1 court から Mid 方向。
- top-down: `(0,300,0), 0,90`。島全体を画面内に収める。雲が一部を遮る。
- underside-low: `(90,24,210), 155,-8`。逆円錐・地層・根・滝の低角度。

## 同一ワールド走査

`scan.md`: **foreign = 563; missing = 0**。5.843 s。
原点±512のx/z、全高度 -64..319。56 region files、対象4225 saved chunks、
939 nonempty sections。未割当chunkは boot-loaded void preset の air。
期待・実際とも1,416,561 blocks。追加ブロック/欠落はない。

- パレット外=0、未定義座標の占有=0。
- 材質差527: grass_block→dirt 524、dirt→grass_block 3。
- 指定 state 差36: farmland moisture 7→0..6。
- 全座標: `mismatches.csv`。各ブロック型の全数は `scan.md`。

内容は通常の random ticking による草/土変化と耕地乾燥に整合する。
凍結や random_tick_speed 変更で差分を隠してはいない。
import直後の Ready 検証通過と、撮影後の厳密な同一性は区別する。
scanner は通常の `BlockData.matches` と同じく、明示された state property を照合。
compact NBT の rail/banner 既定値は実26.3 JARのconstructorで確認した
(`default-state-evidence.txt`)。初回走査の既定値誤判定24件も別ログに残した。

## 変更・検証

- `tools/vfx-shot/vfx-shot.sh`: stock image が通常の起動wrapperを飛ばしても
  void preset を最初のlevel作成前に導入する。新規stackで確認。
- `tools/vfx-shot/map-shot.sh`: 既存 lock / lifecycle / server / client / RCON を再利用した still 撮影。
- `tools/map-gen/capture-highmarch.sh`: **HighmarchInworldIT**。実Paper+実client+全slot走査。
  今回はscan差分により exit 1。厳密一致を弱めず失敗として扱う。
- `tools/map-gen/scan-world.py`: 既存 Anvil/NBT reader を再利用する停止worldのread-only比較。
- `tools/vfx-shot/README.md`、`tools/map-gen/foreign-import-report.md`、area spec の現状を更新。
- 指定 `./gradlew -q compileKotlin :tools:e2e:compileTestKotlin :tools:content-lint:run :tools:architecture-test:test`
  は exit 0。Konsist 80件成功、lint 39 categories / 0 failures、packRef 0 unresolved。
  `validation.log`。シェル構文/Python構文/diff whitespaceも確認。
- unit test、Gradle check、make e2e、push は実行していない。

## 食い違いと残リスク

1. vfx-shot の stock image は通常imageの void-datapack 導入を欠いていた。
   初回はbedrockでReady拒否。今回修正。
2. 旧 foreign report は元画像の島を南 `(0,203)` と解釈していたが、実clientの
   同構図は西 `(-177,-101)` と一致する。同reportを訂正。
3. `.claude/CLAUDE.md` / `.claude/rules/coding-posture.md` は Kotlin2.2 / JVM24 /
   validation warning と記すが、コードは Kotlin2.3.21 / JVM25 / validation error。
   ops-harness 所有なので未編集。
4. 古い証拠の region path は `world/region`。現26.3では
   `world/dimensions/minecraft/overworld/region`。新capture/scanは現コードに合わせた。

要求の exact foreign=0 は未達、cameraは推定、旧fixture sweep再現は未実施、
top-downに雲あり。managerの batched e2e / founder視認は引き続き必要。
草/土・耕地をtick後も不変にする設計は今回変更していない。
