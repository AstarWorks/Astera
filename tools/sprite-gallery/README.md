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
`SPRITE_GALLERY_ROOT=/path/to/Astera` で読み取り対象のリポジトリを変更できる。

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
| 任意のタグ | ソース定義の `tags: [tag, ...]`。未指定なら ID の最初の `-` より前 |
| ノード中間画像 | `build/**/<id>/**/*.png`（ID ディレクトリ直下も対象） |
| VFX 演出 | `content/vfx/*.yaml` / `*.yml` |
| 演出コンタクトシート | `build/vfx-preview/<id>/sheet.png` |

定義・メタ情報・PNG の ID の和集合を一覧化。PNG がなければ「未生成」。
ソースブロックは元の YAML テキストを表示するので `draw:` / `from:` / `graph:`
いずれも表示できる（キーは生成側と同じ lower-kebab、`sprites:` 直下の 2 空白インデント）。
ノード出力先は固定せず `build/` の PNG を探索し、パス中の ID ディレクトリで対応づける。
VFX ディレクトリがまだない checkout では演出タブを空表示する。
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
