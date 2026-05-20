---
status: Accepted
date: 2026-05-20
deciders: Studio maintainer
supersedes: null
superseded_by: null
---

# ADR-0074: Studio: Nuxt 4 SPA + Nitro server migration

## Status

Accepted

## Context

`tools/studio/web/` は ADR-0066 で mainline track に昇格した時点で **Vite 6 + Vue 3 + vue-router (manual) + Hono on Bun (separate process)** という meta-framework なしの構成だった。`bun run dev` は `concurrently` で Vite と Hono サーバを並行起動し、Vite が `/api/*` `/files/*` `/ws/*` を `:3008` の Hono に proxy していた。

問題:

1. **AstarWorks 系の他リポ (AstarManagement / AstarFax / AstarSite / HomePage) はすべて Nuxt** を採用しており、Studio だけが特殊スタック。
2. **dev サーバが 2 プロセス**で起動コスト・落ち際のクリーンアップ・port 管理が手間。
3. file-based routing なし → どのページ・API が存在するかは `src/main.ts` / `server/index.ts` を読まないと分からない (discoverability 低)。
4. Nuxt のエコシステム (DevTools, auto-imports, payload helpers, `$fetch`, `defineEventHandler`) を享受できない。

Astera は Phase 1 PoC closing 中で破壊的変更を歓迎する posture (CLAUDE.md §Pre-release posture)。Studio は dev-only ツール (ADR-0018 / 0066) で外部利用者 0 (ADR-0069 で MIT 公開はしたが API 安定保証なし)。今が乗り換え適期。

## Decision

`tools/studio/web/` を **Nuxt 4 (SPA mode) + Nitro on Bun** に移行する。Frontend は完全に Nuxt の慣習に従う; Backend (Hono) は **Nitro 内の単一 catch-all (`server/api/[...path].ts`) にマウント**するハイブリッドを採る。

### 構成

```
tools/studio/web/
├── nuxt.config.ts        # SPA + Bun preset + WS + CSS
├── app/
│   ├── app.vue           # <NuxtLink> + <NuxtPage>
│   ├── pages/            # /textures /models /effects (file-based)
│   ├── components/       # auto-import
│   ├── composables/      # useStudioWs etc.
│   └── utils/{three,effects}/  # framework-agnostic engines
├── shared/               # frontend ⇄ server 共有
│   ├── types.ts
│   └── effects/{schema,types}.ts
└── server/
    ├── api/[...path].ts  # Nitro → Hono dispatcher
    ├── hono/             # Hono app + routers (intact)
    ├── routes/
    │   ├── ws.ts         # defineWebSocketHandler (crossws)
    │   └── files/...     # 静的ファイル配信 (createReadStream)
    ├── utils/            # paths / state / manifest / asdsl / composite ...
    └── plugins/banner.ts # 起動バナー
```

### レンダリングモード

`ssr: false` (SPA)。Studio は dev-only で SEO 不要、Three.js / WebGL / WebSocket は SSR と相性が悪い。`<ClientOnly>` を一切使わずに済む。

### Hono を保持する理由

純粋 Nitro 化すると 13 個の Hono router (合計約 50 ハンドラ) を file-based に分割する必要がある。Studio の API は密接に関連するルート群 (jobs / tiles / generate がペア、screenshot 系が 4 ハンドラ等) で、ファイル分割は逆に readability を下げる。Hono は Nitro 内で薄い API layer として残し、Nitro は dev server / static serving / WebSocket / catch-all dispatch のみ担当する。

### ADR-0010 / 0066 invariant の維持

- `:plugin:*` runtime / Gradle build graph は依然 Anthropic SDK / MCP SDK に触れない。Nitro は Gradle 外。
- `bun run typecheck` (= `nuxt typecheck`) と `bun test server/` が green。

## Consequences

### Positive

- AstarWorks 系リポと **stack 統一**。新規参加者の onboarding コスト減。
- **dev server が 1 プロセス**。`bun run dev` → http://localhost:3007 で完結 (port 3008 廃止)。
- **file-based routing**: `app/pages/` を見れば全ページ、`server/api/` `server/routes/` を見れば全エンドポイントが分かる。
- **auto-import**: `app/components/**/*.vue`、`app/composables/use*.ts`、`server/utils/*.ts` は明示 import 不要。
- **type safety**: `shared/` を frontend / server 両側から `#shared/...` で参照できる (Nuxt 4 公式の path alias)。
- **Nuxt DevTools** がそのまま使える (component tree / route inspector / payload viewer)。
- **production build** (`nuxt build`) が `.output/server/` を生成し `bun run .output/server/index.mjs` で起動可能 (将来必要になった場合)。

### Negative / Trade-offs

- **Hono は完全には剥がれていない**。pure Nitro file-based を志向するなら将来 split が必要。今は readability 優先で見送り。
- **`Bun.file()` / `Bun.serve()` のような Bun-native API は使えない** (dev mode は Vite が Node 上で走るため)。静的ファイル配信は `node:fs.createReadStream` + h3 `sendStream` に統一。
- **server/utils/asdsl/types.ts** の `Vec3` が `server/utils/project.ts` の `Vec3` と auto-import で衝突 → nitro.imports.dirs を `['utils', 'utils/composite']` に絞って asdsl/ を auto-import 対象外にした。両者は explicit import で参照されているので機能影響なし。
- **`noUncheckedIndexedAccess: false`** に固定 (Nuxt 4 default は true だが、既存 Studio コードは false 前提)。strictness 引き上げは別 PR。

### Mitigations

- ADR-0066 の "subject to keeping `bun run typecheck` and `bun test` green" 条件は **同一コマンドで満たされる** (内部実装は `nuxt typecheck` / `bun test server/` に変わるが script 名は維持)。

## Alternatives Considered

- **Alt A: Frontend のみ Nuxt 化、Hono はそのまま別プロセス**。利点は migration cost が極小。欠点は dev server 2 プロセス継続で「半端な乗り換え」感が残り、aesthetic motivation が満たされない。却下。
- **Alt B: Pure Nitro file-based (Hono を完全排除)**。利点は最大の整合性。欠点は ~50 ハンドラファイル増産 + 既存 router の関連 grouping 喪失。Studio は API 数が多くないがロジックは密接で、現状の Hono router 単位が読みやすい。却下。
- **Alt C: SvelteKit / SolidStart 等への乗り換え**。Vue3 コードベース全捨て・実装コスト過大。却下。

## References

- ADR-0010: postpone-generative-ai-to-phase-6 (AI/SDK は runtime/build に入れない invariant)
- ADR-0018: texture-gen-llm-carveout (Studio scaffolding の起源)
- ADR-0066: promote-studio-to-mainline (mainline track 化)
- ADR-0069: studio-public-release (MIT 公開)
- [Nuxt 4 docs](https://nuxt.com/docs/4.x/getting-started/introduction)
- [Nitro WebSocket (crossws)](https://nitro.unjs.io/guide/websocket)
