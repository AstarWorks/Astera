# スプライト / VFX ギャラリー

ビルド・CDN・追加の実行時依存なし。Bun 1.3.5 以上で起動する LAN 向け閲覧ツール。

```sh
make sprite-gallery                  # foreground, 0.0.0.0:8098
make sprite-gallery-up               # detached; この checkout のデータを配信
make sprite-gallery-status
make sprite-gallery-down
PORT=8100 HOST=127.0.0.1 make sprite-gallery-up
```

通常のアクセス先: **http://192.168.11.5:8098**。設定変更は down → up。
PID・ログは `build/sprite-gallery/`。skill-compare の deploy follower と異なり、
この checkout をそのまま配信し、Git checkout・コンテナ操作は行わない。
foreground の `make sprite-gallery` は `SPRITE_GALLERY_ROOT=/path/to/Astera` で読み取り対象を変更できる。
`make sprite-gallery-up` は常に起動元のcheckoutを配信する。

- スプライトを canvas で同時再生。ID・タグ検索、カテゴリ、4× / 8×、1–60 fps、停止。
  大きい素材は一覧の 4× 表示を 200 px 以内に収め、8× ではその倍に拡大する。
  詳細プレイヤーは元サイズの正確な 4× / 8×（スクロール可能）。
- 黒・グレー・チェッカー・ゲームの空（色と地平線の近似）の背景。
  加算合成は暗い背景に canvas の `lighter` で合成する素材確認用。
  Minecraft の shader・発光・カメラを再現するものではない。
- カードをクリック: 大きいプレイヤー、フレームスクラブ（操作すると停止）、
  「連続再生」で復帰、全フレーム、ソース YAML、中間画像。Esc で閉じる。
- 2 秒ごとに再取得。生成 PNG・メタ情報・ソース・中間画像・コンタクトシートの
  追加・更新・削除を反映。詳細を開いたままでも更新する。

## 読み取り先

`SpriteSources.kt` の出力はすべて **content ディレクトリ相対**。

| 用途 | リポジトリ相対パス |
| --- | --- |
| 横連番 PNG | `content/textures/generated/sprites/<id>.png` |
| フレーム数・サイズ | `content/sprites/<id>.yaml`（ソースより優先） |
| ソース定義 | `content/textures/sources/sprites.yaml` の `sprites.<id>` |
| 任意のタグ | ソース定義の `tags: [tag, ...]`。未指定なら ID の最初の `-` より前（kebab-case 文字列の配列） |
| ノード中間画像 | `build/**/<id>/**/*.png`（ID ディレクトリ直下も対象） |
| VFX 演出 | `content/skills/<id>/look.yaml` |
| 演出コンタクトシート | `build/vfx-shot/<id>/side/sheet.png`（実機優先） / `build/skill-preview/<id>/side/strip.png` |

定義・メタ情報・PNG の ID の和集合を一覧化。PNG がなければ「未生成」。
ソースブロックは元の YAML テキストを表示するので `draw:` / `from:` / `graph:`
いずれも表示できる（キーは生成側と同じ lower-kebab、`sprites:` 直下の 2 空白インデント）。
ノード出力先は固定せず `build/` の PNG を探索し、パス中の ID ディレクトリで対応づける。
演出を持つ skill ディレクトリを列挙する。
画像は上記データから登録したものだけを配信し、任意のファイルパスは公開しない。
大きい build ツリーでは PNG 探索量に応じてポーリング応答が遅くなる。

## ブラウザ統合検証 / スクリーンショット

skill-compare にインストール済みの `playwright-core` と Chromium を再利用する。
このツールの通常起動には不要。

```sh
make sprite-gallery-up
bun tools/sprite-gallery/scripts/gallery-browser.e2e.mjs
# 他 checkout の既存依存を使う場合:
PLAYWRIGHT_PACKAGE_ROOT=/IdeaProjects/Astera/tools/skill-compare \
  bun tools/sprite-gallery/scripts/gallery-browser.e2e.mjs
```

`GALLERY_URL`（既定 `http://127.0.0.1:8098`）、`CHROMIUM_PATH`（既定 `/usr/bin/chromium`）、
`GALLERY_SHOT`（既定 `/tmp/claude-1000/vfx-nodegraph/gallery-shot.png`）を指定可能。
実データの一覧・canvas・検索・スクラブ・設定を確認し、隔離した一時ディレクトリと
別 Bun サーバーで graph YAML・中間画像・VFX シート・未生成・自動更新を確認する。
ゲームサーバー、content 原本、Gradle、unit tests、`make e2e` は操作しない。

## Minecraft 表示 / sample-*

「Minecraft 表示」は nearest、alpha < 0.1 discard、70° 縦視野角、距離3/5/10ブロック。
1ブロック目盛りを空・地面に重ねる。辺長は skill の plate.size（ブロック）を読む。
plate がなければ4ブロックの仮寸法。照明・shader・Minecraft の描画順は近似しない。
このモードでは加算・背景・ピクセル拡大を無効化。通常再生は20 fps。

`sample-` は64px / 8フレームの14グラフ。通常11種はv2 skillの4ブロックplate、
frame_ticks: 1、40tickの一覧用ループ。残る3種も40tickのデモskillを持つ。
dissolve-sigilはdissolve、energy-orbはemissive-pulse、heat-hazeはuv-flowを
同じsprite画像のアニメーション付き4ブロックmeshに適用する。カードにshader_effectを表示。
meshはside向き固定で、atlasアニメーション位相はcast開始と同期しない。
VFXタブは `build/vfx-shot/<id>/side/sheet.png` の実機シートを最優先する。
未撮影ならengineプレビュー、次に「スプライト寸法シート（近似）」を表示する。

ブラウザ統合確認（実データ・α cutoff・距離・全14種・14デモ・シート・スクラブ）:

```sh
PORT=8099 make sprite-gallery
PLAYWRIGHT_PACKAGE_ROOT=/IdeaProjects/Astera/tools/skill-compare \
  GALLERY_URL=http://127.0.0.1:8099 bun tools/sprite-gallery/scripts/samples-browser.e2e.mjs
```

スクリーンショット: `/tmp/claude-1000/vfx-nodegraph/samples-shot.png`。

全14種の実機撮影（専用 `astera-shot-*` のみ）:

```sh
for dir in content/skills/sample-*; do
  SHOT_REAP_SCOPE=self SHOT_KEEP=1 ITEM=shield-bulwark DISTANCE=14 RES=640x360 \
    VIEW=side TICKS=40 SHOT_SLOW_RATE=2.5 make vfx-shot SKILL="${dir##*/}"
done
make vfx-shot-down
REQUIRE_SAMPLE_CAPTURES=1 GALLERY_URL=http://127.0.0.1:8099 \
  PLAYWRIGHT_PACKAGE_ROOT=/IdeaProjects/Astera/tools/skill-compare \
  bun tools/sprite-gallery/scripts/samples-browser.e2e.mjs
```

フレームは `build/vfx-shot/<id>/side/frame_000.png`〜、シートは同じディレクトリの `sheet.png`。
共有8098のプロセスはmanagerが再起動する。他checkoutのプロセスは停止しない。
