# Astera — Claude Code workspace guide

Load this before generating code or making infrastructure changes inside
`Astera/`. Detail lives in `.claude/rules/*.md` and the docs linked below.

## Ground truth

- **`README.md`** — what Astera is, status, quick start.
- **`docs/INDEX.md`** — full documentation index.
- **`docs/architecture/principles.md`** — design principles.
- **`docs/adr/`** — recorded decisions, immutable once Accepted.
- **`docs/IMPLEMENTATION-ROADMAP.md`** — 12-tier master roadmap (SSOT for ordering).
- **`docs/RTM-PARITY-TRACKER.md`** — feature × state matrix (SSOT for "what's left").

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

## Session bootstrap (Wave protocol)

When the user triggers a `Wave Nx` prompt, read in this order **before**
spawning any worker:

1. The wave-specific handoff plan if user provided its path (otherwise skip)
   — note: previously hard-coded as `/home/node/.claude/plans/...-handoff.md`, but
   those are session-scoped and not persistent across runs. The current wave's
   handoff path should come from the user's prompt or be regenerated as needed.
2. `docs/IMPLEMENTATION-ROADMAP.md` (this wave's row + cross-deps)
3. `docs/RTM-PARITY-TRACKER.md` (this wave's row)

Then run Phase A (orchestrator solo: dep prep, mkdir, TaskCreate) → spawn
Phase B workers in parallel per the handoff's worker table → Phase C
(orchestrator solo: wire `platform/Astera*Plugin.kt`, write ADR, run
`./gradlew check`, sync RTM, single commit). **Claude pushes `main` itself**
(founder authorization 2026-10-22, no per-push approval) once `./gradlew check`
is green; never force-push. Full protocol in `.claude/rules/wave-execution.md`.

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
   Paper without WeaponMechanics / Oraxen / MythicMobs / ProtocolLib.
5. **Versions live only in `gradle/libs.versions.toml`.** No hardcoded
   version strings in module `build.gradle.kts` files.
6. **No `kotlinx.serialization` annotations in `*.domain.*`.** Use a DTO in
   `application/config/` (kaml-annotated) and convert to the domain type.
7. **Application APIs do not expose raw JVM throwables.** Return
   `Result<T, E>` with a sealed error hierarchy.
8. **`content/languages/en.yaml` ↔ `ja.yaml` key parity** must hold (i18n
   parity test fails on drift).

## Where to find the detail

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
- `.claude/agents/rtm-sync` — Phase C RTM updater (scoped to `docs/RTM-PARITY-TRACKER.md`).
- `.claude/skills/wave-start` — primes context with the 4-doc read order.
- `.claude/skills/adr-new` — scaffolds a new ADR from the template.
- `.claude/hooks/konsist-watcher.sh` — PostToolUse, records dirty modules in
  `.claude/state/konsist-dirty.txt`, never blocks.

## Session handoff (auto-baton)

**Hand off, don't ask to hand off.** When you checkpoint / pause / hit an
incomplete or blocked state — or whenever you'd ask the user "should I
continue or stop / fresh-session this?" or "残りも進める？" — **do not
ask**. Refresh `.claude/state/handoff.md` immediately so the user has a
paste-ready next-session prompt, then state what shipped and what's next
in chat. Asking about **requirements / intent** is right; making the
user instruct a mechanical next-action you can just produce (the handoff,
the continue/stop call) is not. Never leave the user as the thing that
has to act next when you can produce the artifact. (Mirrors AstarManagement
CLAUDE.md §"Hand off, don't ask to hand off".)

**Always write `.claude/state/handoff.md`** when any substantive work
happened this session (≥1 commit, ≥1 wave shipped, ≥1 non-trivial
decision, or significant state change like a push / merge / branch
cleanup). The user explicitly does not want to manually hand context
between sessions — this file IS the baton.

Trigger automatically:
- After the last commit/merge of a wave when no further work is planned this turn.
- After a meaningful state change the next session must know about
  (push to `origin/main`, branch deletes, worktree removes, dep adoption,
  posture-shift ADR landing).
- Whenever you would otherwise ask "続けるか止めるか" / "次の session 行く？" /
  "should we continue / split / stop?" — write/refresh handoff first,
  THEN report; the user can act on the artifact.
- Before responding "残り次回" / "let's stop here" / similar.
- When the user types `/handoff` (write it then; don't end the session).

**Do NOT write it for**:
- Conversational turns (questions answered without code change).
- Read-only exploration that produced no commit and no decision.
- Mid-wave pauses where the work obviously continues next message.

### File location

Single file, **overwritten** each handoff: `.claude/state/handoff.md`.

(No git-track. `.claude/state/` is already gitignored per Astera convention.
History lives in commits + ADRs; handoff is a moving snapshot for the *next*
session's entry point, not an archive.)

### Required sections (keep tight — this gets pasted as a prompt)

```markdown
# Session handoff — <ISO date>

## Where we left off
- main HEAD: `<short-sha>` "<commit subject>"
- Unpushed: N commits
- Build: ./gradlew check <green | failing on X>
- Konsist: N rules <green | failing>

## What shipped this session
- Wave Nx (ADR-NNNN) — 1-line summary
- Wave Ny (ADR-NNNN) — 1-line summary
(one bullet per merged wave; link to ADR; no LOC, no diff)

## Live decisions / posture changes
- Any rule shift, ADR supersede, naming convention divergence,
  policy decision the user made mid-session. None ⇒ skip section.

## Pending — ranked
1. <highest-priority next work> — why it matters + the one or two
   files / ports / allowlist entries it touches
2. <next> — same shape
3. <next> — same shape
(3-7 items. Pulled from ADR-0072 / ADR-0134 allowlists + IMPLEMENTATION-
ROADMAP cross-deps + any user-stated future intent this session.)

## Resume prompt
Paste-ready, one paragraph, addressed to next-session Claude:

> 前 session で <X / Y / Z> を ship 済み (main HEAD `<sha>`)。
> 残り priority: <#1 from Pending>。<one-line context the next session
> needs to act without re-reading: which ports / which ADR allowlist
> entry / which plugin>. `.claude/CLAUDE.md` の Session bootstrap +
> Wave protocol を読んで Phase A から始めて。
```

The "Resume prompt" section is the load-bearing one — the user copies that
verbatim into the next session's first message. Keep it self-contained
(absolute file paths, ADR numbers, commit SHAs) so the next session can
act with zero re-discovery.

### What NOT to put in the handoff

- Code snippets (commit + ADR have them).
- Verbose explanations of why each wave was done (commit message has them).
- "Things I learned" / "things I tried" (memory system handles those).
- Pending items the user has explicitly deferred to Phase 2+ unless they
  unblock the immediate next priority.

Write tight. Under 80 lines total. If a section needs more, it belongs in
an ADR, not the handoff.

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
