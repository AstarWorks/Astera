---
status: Accepted
date: 2026-05-19
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0059: Fake MC Server + Scenario DSL (Wave _infra-1)

## Status

Accepted

## Context

Astera は Phase 1 PoC closing 中で、Wave 5a〜5c が landed した時点で 125 個の
test がある。だがその粒度分布は:

| Tier | 件数 | 例 |
|---|---|---|
| Unit (domain) | 62 | `ResultTest`, `MatchStateMachineTest` |
| Unit (application / use-case) | 51 | `FireWeaponUseCaseTest`, `ChargedSkillCastUseCaseTest` |
| Smoke / scenario (cross-plugin end-to-end) | **2** | `MatchLifecycleSmokeTest`, `KillRewardChainSmokeTest` |

scenario テストが 2 個しか無いのは、**毎回ゼロから「全 fake adapter + 全
InMemory repo + 全 listener subscription」を手で組み立てる必要がある**ため。
KillRewardChainSmokeTest だけで 80 行、その大半が wiring。

Wave 5a-fix の `ArmorReduction` 導入や Wave 5b の HUD ports 追加で
collaborator が増え続け、新 scenario テストの初期コストは線形に肥大する。
Phase 1 PoC closing 後の Wave 5d〜5f / 6a〜6b では複数 game mode / siege
content が入るので、scenario テストの欠如はそのまま「Claude (AI) が PDCA を
回せない」「人間も追従できない」 ジレンマに直結する。

並行して、Phase B Wave 実装で Claude が「武器 YAML を書く → 発射 → ダメージ
→ キル → 報酬」 のチェーンを **Paper を起動せずに** 検証できる必要がある。
MockBukkit / MockPaper 系の導入は ADR-0002 (vendor-neutral) と過去の方針
(`plugin/test-fixtures` README コメント) で却下されている — 既存
fake/InMemory 群を拡張する路線を選ぶ。

## Decision

**`plugin:test-fixtures` の core-port fake セットを拡張し、`plugin:stats/src/test/`
に `AsteraTestHarness` + `ScenarioDsl` + 5 個の canonical scenario test を追加
する**。要点:

### 1. Worker 分離 (`plugin:test-fixtures` vs `plugin:stats/test/`)

`plugin:test-fixtures` は `:plugin:core` のみに依存する (build.gradle.kts
で固定)。よって以下の二層構造を採用:

- **`plugin:test-fixtures` 配下 (core-port fakes のみ)**:
  - `FakeMatchTeamLookup` — `IMatchTeamLookup` (core)
  - `FakePlayerRepository` — `IPlayerRepository` (core)
  - `FakeGuiAdapter` — `IGuiAdapter` (core) + `GuiOperation` sealed log
  - `helper/BroadcasterHelper.kt` — reified `capturedOf<T>()` + AssertJ assertion helper
- **`plugin:stats/src/test/.../scenario/` 配下 (feature-spanning artifacts)**:
  - `fake/FakeProjectileService.kt` — `IProjectileService` (weapons)
  - `fake/SeededDamageRandomizer.kt` — `IDamageRandomizer` (combat) with deterministic `Random(seed)`
  - `AsteraTestHarness.kt` — bundles every fake + every InMemory production class
    + use cases + listener subscriptions in one constructor
  - `ScenarioContext.kt`, `ScenarioDsl.kt` — `scenario { … }` entry point, `fire(attacker, weapon) at target` builder, `kills(...)` synthetic-kill helper, AssertJ-flavored assertions
  - 5 canonical scenario tests

### 2. AsteraTestHarness ergonomics

```kotlin
val harness = AsteraTestHarness.withWeapons(weaponSpec("amethyst-sword"))
val attacker = harness.spawnPlayer("alice", team = TeamId(/* red */))
val target = harness.spawnPlayer("bob", team = TeamId(/* blue */))

scenario(harness) {
    kills(attacker, target, with = WeaponId("amethyst-sword"))

    target.shouldBeDead()
    eventBus.shouldHavePublished<XpGranted> { it.playerId == attacker.id }
}
```

10 行で end-to-end が書ける。今後 weapon を追加するたびに 1 ファイル増えるだけ。

### 3. AssertJ classpath 公開

`helper/BroadcasterHelper.kt` は AssertJ を使う一方で `plugin/test-fixtures/src/main/`
配下に存在する (cross-module consumption のため)。よって
`plugin/test-fixtures/build.gradle.kts` に **`api(libs.assertj.core)`** を追加し、
downstream test source set が AssertJ を自動的に解決できるようにする
(既存の `api(libs.junit.jupiter.api)` と同じ理由)。

### 4. 5 scenario test の Definition of Done

`./gradlew :plugin:stats:test --tests "*scenario*"` 全 5 個 pass:

1. **AmethystSwordKillsTargetScenarioTest** ✓ Fully verifiable — kill chain → kills==1 + XP > 0 + exactly 1 PlayerKilled + 1 XpGranted
2. **ArmorReducesDamageScenarioTest** ◐ Partial — `ArmorReduction.forAttribute(PHYSICAL)` の値計算まで検証。end-to-end damage flow (DamageGateway / ArmorEquipmentProvider / StatusEffectContainer fake が未整備) は Wave _infra-2 で完成
3. **MatchEndsOnTeamWipeScenarioTest** ◐ Partial — admin-ended match で MatchEnded event 発火を検証。Auto team-wipe detector (winner=red) は Wave _infra-2
4. **NormaProgressOnObjectiveScenarioTest** ✓ Fully verifiable — kill 1 件で MissionProgress.current==1
5. **SkillCooldownScenarioTest** ✓ Fully verifiable — cooldown 20 tick で 10 tick 時点で block + 21 tick で clear

Partial 2 件は意図的: 残 fake (`FakeDamageGateway` / `FakeArmorEquipmentProvider`
/ `FakeStatusEffectContainer` / auto-MatchEnd hook) は Wave _infra-2 (DataDriven
Contents) で `IDamageGateway` の port 整理と合わせて追加する方が clean。

## Consequences

### Positive

- **Claude PDCA loop が一気に短縮**: 武器 YAML → scenario test → assert の最短ループが 10 行
- **scenario テスト追加コストが線形 → 定数**: harness factory の 1 行追加だけ
- **既存 smoke test 2 個も harness に移行可** (Wave _infra-2 でやる)
- **Konsist は green のまま**: `test-fixtures must not import Minecraft or any feature plugin()` テストが新 fake 4 個に対しても pass を確認済み
- **AssertJ 公開で BroadcasterHelper 以外の cross-module test helper も同じ場所に追加可** (今後の deferred test infra に効く)
- **forward-only**: Phase 1 PoC closing 中の破壊的変更ポリシー (CLAUDE.md §Pre-release posture) に整合。bridge code / deprecation alias 一切なし

### Negative / Trade-offs

- `AsteraTestHarness` は `plugin:stats/src/test/` に置くため、**scenarios that need economy are blocked** — Stats ← Core, Weapons, Combat, Match (Economy は見えない)。currency reward の scenarios は plugin:economy 側にミラー harness を作るか、Wave _infra-2 で `:plugin:test-scenarios` 新規 module を作る選択
- 2 / 5 の scenario が partial (TODO(infra-2) コメント残)。end-to-end の完成度は 60%
- Worker B が直接 use case 群を wire するため、wire リストは IMPLEMENTATION-ROADMAP の listener 追加に追従するメンテが必要 — RewardOnKillListener / MissionProgressOnKillListener と同じ追加が走る都度 harness 更新
- AssertJ を test-fixtures `api` に上げたため、`./gradlew :plugin:test-fixtures:jar` の transitive dep に AssertJ が含まれる。test-only consumer なので production 影響なしだが、treedep を見たときの説明が必要

### Mitigations

- partial scenarios には `// TODO(infra-2)` コメントで Wave _infra-2 の改修対象を明示
- `:plugin:test-scenarios` 新規 module 案は Wave _infra-2 のスコープで再評価。economy が絡む scenario が初登場するタイミングで決定
- harness の wire 漏れは Phase C の `./gradlew check` でほぼ catch される (新 use case がテストされていれば、その fail で wire の必要が露見)
- `api(libs.assertj.core)` の影響は README 1 行 update で吸収 (Wave _infra-2 ADR-0060 で実施)

## Alternatives Considered

### Alt A: MockBukkit / MockPaper 系を導入

却下。ADR-0002 vendor-neutral と矛盾、Konsist の `test-fixtures must not
import Minecraft` 違反、Paper 26 で MockBukkit が pin されていない。既存
`Fake*` 群を捨てることになりコスト > 利益。

### Alt B: `:plugin:test-scenarios` 新規 module をいきなり作る

却下。Phase 1 PoC closing 中に新規 Gradle module 追加は overhead が大きい。
`plugin:stats` の testImplementation が既に weapons/combat/match/test-fixtures
を持つので、stats/src/test/ 配下に置けば追加コストゼロ。economy が絡む
scenario が初登場するまでは over-engineering。

### Alt C: Worker B も `plugin:test-fixtures` 配下に書く

却下。test-fixtures の build.gradle.kts コメントで明示されている通り
"Legacy fakes that needed Weapons / Economy types were removed; each feature
plugin reintroduces its own fakes inside its own test-fixtures source set"。
このルールを破ると ADR-0022 sibling-isolation が崩れる方向に圧力がかかる。
test-fixtures は core-only を死守する。

### Alt D: physics-accurate `FakeProjectileWorld` (projectile arc + collision)

却下。`IProjectileWorld` という port は存在しない (現状の port は
`IProjectileService` のみ、spec + spawn のみ)。physics 模擬には新 port +
adapter + fake の三層が必要で scope explosion。**既存 smoke test の idiom
("PlayerKilled を synthetic publish する")** を採用する方が cheap で、
scenario 表現力も損なわない。Paper の本物の projectile は Wave 6a/6b で
hijack するので、それ以前に simulation を書くのは sunk cost になる。

## References

- [[adr/0001-clean-architecture-with-mc-as-edge]] — domain は Paper 非依存
- [[adr/0002-vendor-neutral-mc-adapter-layer]] — adapter/api の Bukkit 禁止 (Konsist enforce)
- [[adr/0017-adopt-datadriven-contents-pattern]] — Wave _infra-2 (ADR-0060) で supersede 予定
- [[adr/0022-multi-plugin-portable-architecture]] — sibling-isolation (test-fixtures は core-only)
- [[adr/0027-phase-1-poc-closing-scope]] — Phase 1 closing posture
- [[adr/0030-combat-depth-and-damage-event-decomposition]] — `ApplyDamageUseCase` の collaborator 列 (TODO(infra-2) の対象)
- 既存 smoke tests: `plugin/match/src/test/.../MatchLifecycleSmokeTest.kt`, `plugin/stats/src/test/.../KillRewardChainSmokeTest.kt`
- Wave _infra-1 plan: `/home/node/.claude/plans/astera-fake-minecraft-server-claude-code-twinkling-salamander.md`
