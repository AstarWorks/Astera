---
status: Accepted (Studio extracted to AsteraStudio repo on 2026-05-20 per ADR-0079; this file is preserved here for historical cross-reference, primary copy now lives in AsteraStudio/docs/adr/)
date: 2026-05-20
deciders: Studio maintainer
supersedes: 0074 (partially — Hono-inside-Nitro decision only)
superseded_by: null
---

# ADR-0078: Studio: pure Nitro file-based API surface

## Status

Accepted

## Context

ADR-0074 で `tools/studio/web/` を Nuxt 4 SPA + Nitro 構成に移行した際、API layer は **Hono を Nitro 内に温存**する hybrid を採った。理由は「13 個の Hono router を 50 個近い file-based handler に分割するのは boilerplate が膨らむ」。

直後の commit `f2cd683` で feature-folder restructure を行い構造は整理されたが、hybrid 構成自体が以下の長期 cost を抱えていた:

1. **二重 routing system** — `server/api/[...path].ts` catch-all → Hono dispatcher の二段構え。新規貢献者は「ここは Hono?Nitro?」を毎回判断する必要があり、`server/middleware/`, `runtimeConfig`, `$fetch<typeof handler>` 等の Nuxt エコシステム機能が API route 全体に均一に効かない。
2. **`/api/*` の discoverability が低い** — `ls server/api/` では catch-all 1 ファイルしか見えず、実際の endpoint は Hono router を開かないと分からない。
3. **Scale 時に router が太る** — 13 router で grouping benefit を取っていたが、Studio はこの先 features を追加する見込み (skills playground / items preview redux / mob editor 等)。Feature 数が 6-8 に増えた時、個別 Hono router が 400-600L に膨らんで grouping のメリットは薄れる。逆に file-based なら 1 endpoint = 1 ファイルが scale しても保たれる。
4. **AI / LLM コード生成 friendly** — 「`POST /api/X/Y` を追加」→ `server/api/X/Y.post.ts` を作る、で完結。context window が小さい LLM ほど file-based の方が扱いやすい。

長期 aesthetics の判断として **pure Nitro file-based に完全移行**する。

## Decision

`tools/studio/web/server/` を以下に統一する:

- **`server/api/**/*.{get,post,put,patch,delete}.ts`** — Nitro file-based API handler。1 endpoint = 1 file。method 接尾で動詞を表現。
- **`server/utils/{textures,models,effects}/`** — feature-affiliated helper modules。`server/utils/paths.ts`, `server/utils/template.ts` のみ top-level (cross-cutting)。Helper export 名は feature 跨ぎで衝突しないように uniqueness を担保 (例: `Scope` → `TextureScope` / `EffectsScope`、`Vec3` は `server/utils/models/vec3.ts` に共通化)。
- **`server/hono/` ディレクトリ削除**、**`package.json` から `hono` 依存削除**、**`server/api/[...path].ts` catch-all 削除**。
- 各 handler は `defineEventHandler` + h3 helpers (`getRouterParam`, `getQuery`, `readBody`, `setResponseHeader`, `setResponseStatus`, `createError`) を使う。
- 不変: HTTP URL surface、frontend 動作、`/ws` WebSocket bridge、`server/routes/files/**` の static file 配信、59 server unit tests。

ADR-0074 の他の決定 — Nuxt 4 SPA + Nitro on Bun、`ssr: false`、`shared/` 型共有、`server/routes/ws.ts` を crossws で書く、static files を `node:fs.createReadStream` + h3 `sendStream` で配信 — は **不変**。

### file 数の現実

| Feature | Endpoints | Handler files | Helper files |
|---|---|---|---|
| Textures (jobs, tiles, generate, catalog, manifest, categories) | 10 | 10 | 3 (manifest, prompts, catalog) + tiles-lib |
| Models (models, state, paint, cuboids, composite, asdsl, screenshot) | 35 | 35 | model-state, templates, bbmodel-export, texture-paths, persist, crop, project, image-cache, ws-bridge, vec3, cuboids-lib, composite-lib, asdsl-lib, screenshot-lib, models-lib, asdsl/, composite/, schemas/ |
| Effects (effects CRUD, chat) | 9 | 9 | manifest, schema-prompt, prompts, effects-lib, chat-lib |
| Global (health) | 1 | 1 | — |
| **Total** | **55** | **55 handler files** | |

## Consequences

### Positive

- **HTTP surface = `ls server/api/`** で完結。Hono router を開く必要がない。
- **小さい blast radius**: 1 endpoint = 1 ファイル = 1 PR diff。git blame も clean。
- **Nuxt エコシステム統一**: `server/middleware/`, `runtimeConfig`, `$fetch<typeof handler>` 等が一様に効く。将来 `server/middleware/auth.ts` を入れる場合も file-based route 全体に適用される。
- **AI コード生成 friendly**: 「`POST /api/X` を追加」→ 単一ファイル生成で完結、context window が小さくて済む。
- **Hono dep 削除**: `^4.6.14` 1 依存減 (security update / breaking change 追従不要)。
- **`Vec3` / `Scope` の auto-import 衝突解消**: 単一 source of truth (`vec3.ts`) + feature-prefixed names (`TextureScope`, `EffectsScope`)。`nitro.imports.dirs` workaround も不要に → 削除可能 (本コミットでは `dirs: []` で auto-import 自体を opt-out して explicit imports に統一)。
- **長期 scale 性**: feature 数が 6-8 に増えても、各 endpoint ファイルは小さいまま。

### Negative / Trade-offs

- **File count +44** (1 catch-all + 1 app.ts + 3 feature/index.ts + 13 router = 18 → 55 handlers + 5 lib files = 60)。トップレベルの API 探索性向上と引き換え。
- **関連 endpoint の同居 readability 喪失**: 旧 `cuboids.ts` は 5 操作が同ファイルで見えたが、今は 5 ファイルに分散。IDE の "go to file" + folder grouping (`server/api/cuboids/`) で補完。
- **explicit import の冗長性**: 各 handler が `import { X } from '../../utils/...'` で helpers を import。Nitro auto-import を使えば省略可能だが、衝突回避のため意図的に disabled。

### Mitigations

- Feature 間でロジック共有する helper は `server/utils/{feature}/` に集約 (`tiles-lib.ts`, `catalog.ts`, `cuboids-lib.ts`, etc.)。
- handler ファイルを薄く保ち、business logic は helper に委譲する規約。
- `server/utils/models/vec3.ts` のような共通型ファイルで cross-feature 衝突を予防。

## Alternatives Considered

- **Alt A: Hono を保持 (ADR-0074 hybrid)**。短期 readability は良いが、long-term scale で router 太り + Nuxt エコシステム断絶。Rejected。
- **Alt B: Hono router を `server/middleware/` 配下に移して Nitro 中間層として使う**。middleware は全 request に走るので API limited な使い方には不適切。Rejected。
- **Alt C: Nitro auto-import を有効化したまま全部の helper を flat に置く**。`Vec3` 等の名前衝突を毎回 rename で回避するコスト > explicit import 冗長性のコスト。auto-import opt-out 採用。

## References

- ADR-0018: texture-gen LLM carve-out (Studio scaffolding の起源)
- ADR-0066: promote-studio-to-mainline (mainline track 化)
- ADR-0069: studio-public-release (MIT 公開)
- ADR-0074: studio-nuxt-migration (本 ADR が部分 supersede する Nuxt 4 移行 ADR)
- [Nitro routing](https://nitro.unjs.io/guide/routing)
- [Nuxt 4 server/](https://nuxt.com/docs/4.x/guide/directory-structure/server)
