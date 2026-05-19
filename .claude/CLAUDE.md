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

1. `/home/node/.claude/plans/home-node-claude-plans-radius-roadmap-handoff.md`
2. `/home/node/.claude/plans/wave-{N}{x}-handoff.md`
3. `docs/IMPLEMENTATION-ROADMAP.md` (this wave's row + cross-deps)
4. `docs/RTM-PARITY-TRACKER.md` (this wave's row)

Then run Phase A (orchestrator solo: dep prep, mkdir, TaskCreate) → spawn
Phase B workers in parallel per the handoff's worker table → Phase C
(orchestrator solo: wire `platform/Astera*Plugin.kt`, write ADR, run
`./gradlew check`, sync RTM, single commit). **Claude never pushes** — the
user reviews and pushes manually. Full protocol in `.claude/rules/wave-execution.md`.

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
  framework, ADR discipline.
- `.claude/rules/ai-delegation.md` — Phase 1 AI usage policy. Studio +
  model-gen + build-model skill are mainline (ADR-0066); texture-gen is
  still carve-out (ADR-0018).

## Harness helpers

- `.claude/agents/astera-reviewer` — Phase C close review (read-only, Haiku).
- `.claude/agents/rtm-sync` — Phase C RTM updater (scoped to `docs/RTM-PARITY-TRACKER.md`).
- `.claude/skills/wave-start` — primes context with the 4-doc read order.
- `.claude/skills/adr-new` — scaffolds a new ADR from the template.
- `.claude/hooks/konsist-watcher.sh` — PostToolUse, records dirty modules in
  `.claude/state/konsist-dirty.txt`, never blocks.

## What to use

- `make help` — every common operation (build / docker / kind / argocd).
- `make check` / `./gradlew check` — full verification including Konsist.
- `./gradlew :tools:architecture-test:test` — Konsist only.

## Sibling AstarWorks repos

| Path | Why it matters |
|---|---|
| `/IdeaProjects/AstarWorks` | Company hub (sales, team, strategy). |
| `/IdeaProjects/AstarManagement` | Main product (Spring Boot + Nuxt 4). Package convention `com.astarworks.astarmanagement.*`; Astera mirrors with `com.astarworks.astera.*`. **Check its Exposed setup first** when designing persistence. |
| `/IdeaProjects/HomePage` | Public homepage (Nuxt 3). |
