# Coding posture

## Toolchain

- **Kotlin 2.2** / **JDK 25** / **Paper 26.3**.
- Kotlin emits JVM_24 bytecode (the Kotlin 2.2 compiler ceiling). Java
  compiles to JVM_25 so Paper 26 Maven variants resolve correctly. See
  `gradle.properties` — `kotlin.jvm.target.validation.mode=warning` is set
  intentionally to allow this mismatch.
- Gradle 9.5.1 + version catalog (`gradle/libs.versions.toml`) is the **only**
  place version strings live. Konsist will not catch hardcoded versions in
  `build.gradle.kts`, but the orchestrator + `astera-reviewer` subagent will.

## Dependency injection

- **Phase 1: manual constructor injection.** Wiring is small enough that
  explicit is readable. Each plugin's `platform/Astera<Plugin>Plugin.kt` wires
  use cases by hand.
- **Koin annotations land later** — defer until a single plugin's use-case
  count crosses ~5 or a wave introduces dynamic graph construction. Decision
  per ADR-0004 + ADR-0022.

## Cross-plugin coordination

- Core's **`ServiceRegistry`** for port impls (priority-based registration).
- Core's **`IDomainEventBus`** for fire-and-forget cross-plugin events. The
  sealed `DomainEvent` hierarchy lives in `core.domain.event`.
- Never reach into a sibling plugin's package directly. Konsist will fail the
  build.

## Tests

- **Integration tests only; unit tests are forbidden** (founder decision
  2026-10-23: unit tests cost more to maintain than they return). Allowed:
  `tools/game-sim` scenario tests (real wiring), `tools/e2e` (real Paper +
  bots), Postgres `*IT` (Testcontainers), tests that load the real shipped
  `content/` through real loaders (`Shipped*ContentDecodesTest` etc.), and
  Konsist rules in `tools/architecture-test`.
- Do not write tests that exercise one class/use case with fakes or mocks.
  To verify logic, add a game-sim scenario instead.
- `plugin/*/src/test/` may only hold `*IT` or `Shipped*Test` classes or entries
  of `tools/architecture-test/src/test/resources/konsist-allowlist/plugin-tests.txt`;
  Konsist (`TestPolicyTest`) fails the build otherwise. Policy + classification:
  `docs/contributing/testing-policy.md`.
- Cross-plugin in-memory fakes for game-sim live in `plugin:test-fixtures`.
- Konsist tests (`tools:architecture-test`) are part of `./gradlew check`.

## ADR discipline

- Add an ADR **before** a non-obvious cross-cutting decision (DI swap, ORM
  swap, MC vendor swap, new plugin split). Don't ship the change without one.
- One ADR per decision. Never edit a merged ADR (status `Accepted`); supersede
  with a new one instead.
- Use the `adr-new` skill to scaffold from `docs/adr/0000-template.md`.

## Application API surface

- **No raw JVM throwables in application API.** Return `Result<T, E>` with a
  sealed error hierarchy. Konsist enforces this across every plugin's
  `*.application.*` package (see `ArchitectureTest.kt`:
  `application layers must not import raw JVM throwable types`).
- Use `IBroadcaster` / `IMessageRenderer` / `IDomainEventBus` — never raw
  `Bukkit.broadcastMessage(...)` from application/.

## Pre-release breaking changes (Phase 1)

CLAUDE.md §Pre-release posture を細則化。Phase 1 PoC closing 中は破壊的
変更を **積極的に** 採用する:

- **Field / method rename**: 旧名の deprecated alias は残さない。一括
  rename + caller 全更新で終わり (Wave 5a-fix の `ArmorSpec.damageReduction`
  → `reduction: ArmorReduction` がモデルケース)。
- **Sealed hierarchy 削除 / 再編**: subclass を一掃して書き直すのも自由。
  exhaustive `when` の更新は build-fail で catch される。
- **YAML schema 変更**: 新 schema が clean なら旧 schema 用 dual-decoder は
  書かない。既存 `content/` を新 schema に手で書き換える方が後で読みやすい。
- **DB migration**: up-only。down migration は書かない。テスト DB は
  Flyway の clean target で wipe-and-rebuild。
- **テストの大規模書き換え**: 設計が変わったら test も書き直す (preserve
  目的の dual-test は書かない)。
- **ADR supersede**: 既に Accepted 状態の ADR でも、設計が良くなる方向なら
  迷わず supersede ADR を起草。Status のみ更新、本文は immutable。

**やってはいけないこと**:
- `@Deprecated` を残す (リリース前の deprecation は単なる decay)
- 旧 type を `typealias` / fallback shim にする
- 「移行期間」「将来削除予定」コメントを残す
- 旧 caller の挙動を保証するための conditional branch を加える

**例外** (ADR discipline は維持):
- 既存 ADR の決定を覆す場合 — supersede ADR を起草 (本文 immutable、Status
  だけ `Superseded` に更新)。本文を直接 rewrite するのは NG。

**自動化が来ても変わらない**: Konsist は破壊的変更を catch しない
(関心は import 方向と type purity)。Worker / 別 session が破壊的変更を
入れる際の guardrail は本 rule + Phase C `astera-reviewer` subagent の
判断のみ。
