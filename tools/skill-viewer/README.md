# :tools:skill-viewer

生成済みプレビューの閲覧: `make skill-compare-up` → http://192.168.11.5:8097/viewer （3D プレビュー）。

現行 renderer は `astera-sim-trace/2` を読む headless three.js renderer (ADR-0301)。
`src/preview2.js` / `src/preview2.html` を `bun run build` で `dist/` にまとめ、
`scripts/trace-preview.mjs` が trace・モデル・テクスチャ・case のカメラを注入して描画する。
配信 plugin には含まれず、`check` の対象外。実クライアント shader の完全再現ではない。

```sh
cd tools/skill-viewer
bun install --frozen-lockfile
bun run build
# repository root:
make skill-preview SKILL=couture-rapier-s2
```

Bun、playwright-core が起動できる Chromium、Minecraft client jar のローカル assets が必要。
trace は `tools/game-sim/build/sim-traces/v2/<skill>.json`。生成は manager の scenario 実行で行う。
case は `tools/skill-compare/data/<case>/`。詳細とオプションは
[scripts/trace-preview.mjs](scripts/trace-preview.mjs) と [SIM-TRACE-V2](../vfx-shot/SIM-TRACE-V2.md)。

出力は `build/skill-preview/<skill>/` の `strip.html`、`strip.png`、`frames/`、`ref/`、`meta.json`。
近似表示は skill-compare の「3D プレビュー」から選択する。実クライアント比較は「スキル比較」または「撮影結果」。
`strip.html` の headless 用 `file://` 画像 URL は hub が固定出力先の HTTP URL に変換する。
`--out <dir>` を使う場合も hub の固定出力先内に置く。

旧 trace-v1 の `dist/index.html`、`window.viewer`、`scripts/render.mjs` と
旧 `skill-3d` / `src/viewer3d.js` は現行コードに存在しない。`dist/preview2.html` は config 注入なしでは再生しない。

Display 変換の背景は [DISPLAY-SEMANTICS.md](DISPLAY-SEMANTICS.md)。
校正用の `scripts/calibrate.mjs` と Gradle `calibrate` は manager の検証対象。生成済みの校正画像も
「3D プレビュー」の `tools/skill-viewer/build/` から閲覧できる。
