# Astera — Claude Code workspace guide

Load this before generating code or making infrastructure changes inside
`Astera/`. Detail lives in `.claude/rules/*.md` and the docs linked below.

## Ground truth

- **`README.md`** — what Astera is, status, quick start.
- **`docs/INDEX.md`** — full documentation index.
- **`docs/architecture/principles.md`** — design principles.
- **`docs/adr/`** — recorded decisions, immutable once Accepted.
- **`docs/vision/VISION.md`** + **`docs/vision/areas/*.md`** — vision, per-area current state / ideal / open questions (SSOT for "how it is").
- **The Board** (claude.ai Artifact, `.claude/rules/board.md`) — SSOT for "what is next and how far it got". ADR-0266.

## Pre-release posture (Phase 1 PoC closing)

Astera は **Phase 1 PoC closing 中** (Wave 5a–5f → Wave 6a–b で end-to-end
PoC 達成予定)。リリースまで外部利用者は 0 なので、**後方互換性は要求しない**:

- public API の field / method 名はいつでも rename / remove 可
- YAML schema の field 名・形は freely 破壊的変更 (dual-decoder / fallback
  shim は書かない)
- DB migration は up-only (down は不要、テスト DB は wipe-and-rebuild)
- 既存 ADR の supersede / withdrawal は躊躇しない (ADR Status 仕様内で正規)
- 既存テストの大規模書き換えは新設計が clean になるなら積極的に

**やらないこと**: `@Deprecated` を残す / 旧 type を fallback shim にする /
旧 YAML 形を accept する dual-decoder を書く / 「移行期間」を設ける。

**なぜ**: bridge code はリリース後に正解設計を歪める負債。今のうちに**真に
長期的に効く設計**を選ぶための余裕を作る。Wave 5a-fix が `WeaponDamageRule`
を一方的に削除し `ArmorSpec.damageReduction: Double` → `reduction:
ArmorReduction` に置換したのが好例 (ADR-0030/0058 の forward-only commit
方針と整合)。

**境界**: Phase 1 PoC を脱した時点で本 section を supersede する別 ADR
(post-release compatibility policy) を起草。それまでは破壊的変更を恐れない。

## Main session = manager only (founder rule, 2026-10-23, permanent)

The main (interactive) Claude session in Astera is a **manager**, never an
implementer — in every session, not just one:

- **Does:** understand the founder's goal, decide direction, split work, write
  agent/Codex prompts, merge agent branches locally, push `main`, deploy, read
  logs to judge outcomes, report to the founder.
- **Does not:** edit product code, content, tests, build or infra files itself;
  investigate by reading large amounts of code; debug hands-on. Anything beyond
  a one-line merge-conflict resolution or a commit-message-level fix goes to a
  subagent (Sonnet/Haiku) or Codex (`codex exec -p astar-implement`).
- If the main session catches itself editing files, it stops and delegates.
- **Enforced** by the `PreToolUse` hook `.claude/hooks/manager-guard.py`
  (main session only; subagents/Codex unaffected). Allow/deny tables, Codex
  launcher (`scripts/codex-task.sh`, always via Bash `run_in_background: true`;
  gated by `.claude/hooks/gate-codex-lane.py`) and prompt preambles:
  `.claude/rules/manager-mode.md`.

## Session bootstrap (track protocol)

Work is organised by **track** (one area of the game, one worktree, one session) and tracked on the
**Board**. Before spawning any worker, read in this order:

1. `docs/vision/VISION.md` - pillars, scope, out-of-scope, the founder's yardstick.
2. `docs/vision/areas/<area>.md` for the area you touch.
3. The Board (https://claude.ai/artifact/2D2a8S41RpDwApdZFnPno3, via `ArtifactData`): items, lanes, founder memos.
   Rules: `.claude/rules/board.md`. Ownership, hot files and integration windows: `.claude/rules/tracks.md`.

Tracks compile and add their own e2e/sim tests; the manager merges at the 12:00 / 21:00 JST integration
windows, runs ONE batched `make e2e`, and **pushes `main` itself** once it is green (founder authorization
2026-10-22, no per-push approval; never force-push). Details: `.claude/rules/wave-execution.md`.

## Non-negotiable rules (CI-enforced via Konsist)

Konsist tests in `tools/architecture-test/.../ArchitectureTest.kt` fail the
build on violation. These are the **prose summary**; the test file is the
SSOT.

1. **Minecraft is an edge.** Each plugin's `*.domain.*` and `*.application.*`
   packages MUST NOT import the Bukkit family (`org.bukkit.*`, `io.papermc.*`,
   `org.spigotmc.*`, `com.destroystokyo.paper.*`, `com.mojang.*`). Only
   `*.adapter.paper.*` and `*.platform.*` may.
2. **`core.adapter.api.*` is vendor-neutral.** It defines the MC abstractions
   (`IMcServer`, `IMcPlayer`, `IDomainEvent`, …). Bukkit imports here = build fail.
3. **ADR-0022 sibling isolation** holds per the matrix in
   `.claude/rules/module-layout.md`. Each feature plugin may reference Core +
   its softdepends only.
4. **All external Paper plugins are soft-deps.** Astera must boot in vanilla
   Paper without ProtocolLib.
5. **Versions live only in `gradle/libs.versions.toml`.** No hardcoded
   version strings in module `build.gradle.kts` files.
6. **No `kotlinx.serialization` annotations in `*.domain.*`.** Use a DTO in
   `application/config/` (kaml-annotated) and convert to the domain type.
7. **Application APIs do not expose raw JVM throwables.** Return
   `Result<T, E>` with a sealed error hierarchy.
8. **`content/languages/en.yaml` ↔ `ja.yaml` key parity** must hold (i18n
   parity test fails on drift).

## Where to find the detail

- `.claude/rules/board.md` — Board schema, lanes, single-writer, batch report.
- `.claude/rules/tracks.md` — tracks, file-ownership table, hot files, integration windows.
- `.claude/rules/wave-execution.md` — full Phase A/B/C protocol, Worker禁則,
  off-limits paths, ADR number allocation discipline.
- `.claude/rules/module-layout.md` — 7-plugin layout, sibling-isolation matrix,
  per-plugin internal package structure, "when you touch X" guide.
- `.claude/rules/coding-posture.md` — Kotlin/JDK versions, DI choice, test
  policy (integration tests only; unit tests forbidden), ADR discipline.
- `.claude/rules/ai-delegation.md` — Phase 1 AI usage policy. Studio +
  content authoring tools moved to a separate repo
  ([[adr/0079-extract-studio-to-own-repo]]); plugin-side invariants
  (no AI/ML SDK in `:plugin:*`) are unchanged.

## Harness helpers

- `.claude/agents/astera-reviewer` — Phase C close review (read-only, Haiku).
- `.claude/agents/area-spec-sync` — updates the 現状 section of `docs/vision/areas/*.md` (Haiku).
- `.claude/skills/wave-start` — primes context with the VISION → area spec → Board read order.
- `.claude/skills/adr-new` — scaffolds a new ADR from the template.
- `.claude/hooks/konsist-watcher.sh` — PostToolUse, records dirty modules in
  `.claude/state/konsist-dirty.txt`, never blocks.

## Session handoff (auto-baton) + one worktree per track

**Track = one line of work** (e.g. `vanilla-material`, `limbus-redo`), named by a
stable kebab-case slug — not a session id, so the next session finds it by name.
Several sessions run in parallel; each owns exactly one track.

**Worktree per track (always, not only for parallel waves).** A track lives in
`/IdeaProjects/Astera/.claude/worktrees/<slug>` on branch `wp/<slug>`
(`git -C /IdeaProjects/Astera worktree add .claude/worktrees/<slug> -b wp/<slug> origin/main`,
after `fetch`; launch the session in that dir). The
manager merges agent/Codex branches *there* and ships from there:
`git fetch origin && git merge origin/main && git push origin HEAD:main`.
Never commit, merge, stash or switch branches in the primary checkout
`/IdeaProjects/Astera` — it may be dirty from other sessions. Codex
(`scripts/codex-task.sh`, run from the track worktree) and `isolation: worktree`
agents branch off the track's HEAD.

**Hand off, don't ask to hand off.** Whenever you would ask "続ける？/止める？/
次 session？", pause, hit a blocked state, or the user types `/handoff` —
write the track's handoff first, then report. Write it after any substantive
work (≥1 commit, merge, push, non-trivial decision, branch/worktree change).
Skip it for pure Q&A, read-only exploration, or obvious mid-wave pauses.

**File:** `/IdeaProjects/Astera/.claude/state/handoffs/<slug>.md` — always this
absolute path in the primary checkout (gitignored), even when working in a
worktree. Overwrite only your own track's file; never edit or delete another
track's. Track finished (merged + pushed, nothing pending) ⇒ delete its file
and its worktree. History lives in commits + ADRs, not here.

Format (under 80 lines; no code snippets, no "things I tried"):

```markdown
# Handoff — <slug> — <ISO date>
- Track: <slug> | Worktree: /IdeaProjects/Astera/.claude/worktrees/<slug> | Branch: wp/<slug>
- HEAD: `<sha>` "<subject>" | origin/main: `<sha>` | unpushed: N | build/e2e: <state>

## Shipped this session      (one bullet each, ADR link, pushed or not)
## Live decisions            (founder decisions / rule shifts; omit if none)
## Pending — ranked          (3-7 items: why + the 1-2 files it touches)
## Resume prompt
> `/IdeaProjects/Astera/.claude/worktrees/<slug>` で起動。handoff は
> `/IdeaProjects/Astera/.claude/state/handoffs/<slug>.md`。<state + SHAs>。
> 次: <Pending #1 with absolute paths / ADR numbers>。
```

The Resume prompt is load-bearing — the user pastes it verbatim, so keep it
self-contained (absolute paths, SHAs, ADR numbers).

## What to use

- `make help` — every common operation (build / docker / kind / argocd).
- `make check` / `./gradlew check` — full verification including Konsist.
- `./gradlew :tools:architecture-test:test` — Konsist only.

## Sibling AstarWorks repos

| Path | Why it matters |
|---|---|
| `/IdeaProjects/AsteraStudio` | Content authoring IDE (Nuxt 4 + Nitro on Bun). Edits this repo's `content/{textures,models,effects}/` via `STUDIO_REPO_ROOT`. See [[adr/0079-extract-studio-to-own-repo]]. |
| `/IdeaProjects/AstarWorks` | Company hub (sales, team, strategy). |
| `/IdeaProjects/AstarManagement` | Main product (Spring Boot + Nuxt 4). Package convention `com.astarworks.astarmanagement.*`; Astera mirrors with `com.astarworks.astera.*`. **Check its Exposed setup first** when designing persistence. |
| `/IdeaProjects/HomePage` | Public homepage (Nuxt 3). |
