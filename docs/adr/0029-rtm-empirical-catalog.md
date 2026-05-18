---
status: Draft
date: 2026-05-17
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0029: RTM Empirical Catalog

## Status

Draft

## Context

[ADR-0013 (RTM divergence policy)](0013-rtm-divergence-policy.md) was written
on the assumption that RTM (RyuZUTechnicalMagic) is a **closed-source**
predecessor and that Astera's only RTM reference would be paraphrased design
extracts in `docs/architecture/rtm-divergence.md`.

During the Wave 4.5 audit (commit `68cef2e`), the first audit pass also
assumed RTM was closed-source without verification. A second audit pass
(triggered by user pushback "サブエージェントに任せるとかでもいいから読めよ")
did a WebFetch against `https://github.com/Y-RyuZU/RyuZUTechnicalMagic` and
confirmed: **the repository is fully public**. The previous assumption was
wrong.

This ADR records the empirical RTM data gathered from that fetch so future
contributors and AI agents have a single canonical reference for "what RTM
actually has" rather than relying on paraphrases. It companions ADR-0013
(policy stays unchanged) and feeds into the [[../RTM-PARITY-TRACKER]] and
[[0028-roadmap-realignment-paper-as-edge]] decisions.

## Decision

Record the following empirical findings as canonical references. Future
roadmap / parity decisions cite this ADR rather than re-fetching.

### RTM repository facts

| Property | Value |
|---|---|
| URL | https://github.com/Y-RyuZU/RyuZUTechnicalMagic |
| Visibility | **Public** (confirmed via GitHub API `"private": false`, 2026-05-17) |
| Default branch | `master` (not `main`) |
| Created | February 2024 |
| Last activity | November 2024 |
| Stars | 1 |
| Commits | 28 |
| Primary language | Kotlin 99.6% |
| Paper API target | 1.20.4-R0.1-SNAPSHOT |
| Build | Gradle + Shadow + Writerside docs |
| Custom shared plugin | `dev.ryuzu.shared-build` |

### RTM Gradle module layout (6 modules)

```
:modules:api:core                         — pure Kotlin domain types
:modules:api:minecraft                    — Bukkit-touching API surface
:modules:api:repositories:permanent       — MongoDB repository API
:modules:api:repositories:temporary       — Redis repository API
:modules:core:impl                        — pure Kotlin application impl
:modules:minecraft:paper                  — Paper plugin entry point
:modules:repositories:permanent:mongo     — MongoDB driver impl
:modules:repositories:temporary:redis     — Redis driver impl
```

Comparable to Astera's 7-plugin layout (plugin/core + 6 feature plugins),
but RTM is a single Gradle build with sub-modules whereas Astera is
multi-plugin per ADR-0022 (each plugin loads independently on Paper).

### 7 confirmed game modes (per RTM `GameMode.kt` enum)

| # | Mode | Goal | Source |
|---|---|---|---|
| 1 | **Battle Loyal** | Small-scale gathered-item arena | README + `GameMode.kt` |
| 2 | **Break Core** (primary intended mode) | Destroy enemy core | README + `GameMode.kt` |
| 3 | **Carry TNT** | Transport TNT to enemy base | README + `GameMode.kt` (`CarryTnt`) |
| 4 | **Carry Mini TNT** | Collect 10 mini-TNTs into 1 larger TNT | README + `GameMode.kt` (`CarrySmallTnt`) |
| 5 | **Carry Big TNT** (payload) | Transport an extra-large single TNT charge (`GuiAction.CHANGE_PAYLOAD`) | `GameMode.kt` (`CarryBigTnt`) — NOT in README |
| 6 | **Capture Wool** | Area / region control | README + `GameMode.kt` |
| 7 | **Steal Jewelry** | Retrieve 3 enemy gems | README + `GameMode.kt` (`SteelJewelry`) |
| + | **Battle Royale** | Free-for-all, no team (distinct `IGamePlayer.GamePlayer.BattleRoyalePlayer` sealed variant) | `GameMode.kt` (`BattleRoyale`) — NOT in README |

The 2026-05-17 catalog claimed 6 modes from README only. The deep audit
(2026-05-19) added `CarryBigTnt` (payload, 3rd TNT variant) and
`BattleRoyale` (no-team mode) from `modules/api/core/.../game/mode/GameMode.kt`
sealed enum. **Total 8 sealed values** but `Battle Loyal` and `Battle Royale`
are distinct: the former is small-scale arena (team), the latter is
free-for-all (no team).

Astera Wave 5c (commit `e9808a2`) shipped 6 modes (`BattleLoyal` /
`BreakCore` / `CarryTNT` / `CarryMiniTNT` / `CaptureWool` / `StealJewelry`).
`BattleRoyale` + `CarryBigTnt` deferred to **Wave 7a (Siege Content
Authoring)** since they're content-fidelity rather than mechanic gaps.

### 22 open RTM issues (Nov 2024 snapshot)

| # | Title (jp) | English summary | Astera wave |
|---|---|---|---|
| 1 | 多言語対応 | i18n infrastructure | Wave 1 ✅ (MessageKey + ICU follow-on Phase 2 mid) |
| 2 | ステータス変化の実装 | Status effects (unified vanilla + custom) | Wave 5a (port handler) + Wave 6 (Paper) |
| 3 | エンダーチェストの実装 | EnderChest UI + DB storage | Wave 5e |
| 4 | ショップの実装 | Shop NPCs (non-MythicMobs/Citizens) | Wave 4.5 ✅ |
| 5 | 永続保存データ | Inventory save / prevent actions during load | Wave 5e |
| 6 | 報酬システム | Reward 2-server flow (game→Redis→lobby) | Wave 5e |
| 7 | WeaponMechanicsとの統合 | WeaponMechanics integration | ADR-0015 D (declined) — Astera self-builds |
| 8 | 結界システム | Barrier system + DisplayEntity | Wave 5d |
| 9 | ブロック破壊・設置 | Block place/break + NBT durability (max 100 HP) + pseudo-recovery | Wave 5d |
| 10 | 独自機械 | Redstone dispensers with skill cores, traps | Wave 5d (P2) |
| 11 | スキンシステム | Skill effect cosmetics + GUI + player trading | Phase 4 |
| 12 | 寄付系 | Marketplace + creator revenue + battle pass + cosmetics | Wave 5f + Phase 4 |
| 13 | WorldGuard保護 | Protected zones | ADR-0015 D — self-built `Plot`/`Region` (Phase 5) |
| 14 | HUD作成 | CT / star / level counters in-match | Wave 5b + Wave 6 |
| 15 | 個人設定 | Per-player config storage | Wave 5e |
| 16 | 依存関係整理 | Dependency cleanup / refactoring | ✅ (Astera built clean from start) |
| 17 | チュートリアルの作成 | Tutorial system | Wave 5e |
| 18 | k8sの構成整備 | Kubernetes setup | Wave _ops (Phase 1.5) |
| 19 | 独自通貨の発行と管理 | JP regulatory currency compliance (6mo expiry OR ¥10M cap, non-redeemable, dual-currency) | Wave 5f (P2 legal) |
| 20 | yml構造の修正 | YAML structure fixes | ADR-0016 ✅ (schema SSoT) |
| 21 | スキルの表現力の拡張 | Skill expression: **particle definitions + CEL expression language** | Wave 5f (P3 long-tail) |
| 22 | ゲームコアロジック リファクタリング | Game core listener/controller ↔ service separation | ADR-0001 ✅ (Hexagonal from start) |

(Issue #23 closed: refactor PR landed.)

### RTM Writerside docs (`docs/Writerside/topics/`)

| Doc | Content |
|---|---|
| `BlockState.md` | Block durability system: max HP 100, tag-based categorization, rock-paper-scissors balance (三すくみ) between tools, NBT timestamp self-recovery |
| `アイテム管理に関して.md` | Read-only inventory philosophy (digital-first source of truth), automated warehouse, filter by season/type/category, individual weapon/armor slots, seasonal item lifecycle |
| `コンテンツ案一覧.md` | Post-launch ideas: competitive ranking, tournament automation, 1v1 betting, quick spell assistant, skill tagging (custom/formula langs), patch Discord bot, GPT-4o-mini translation, decentralized hosting |
| `タスク.md` | TODO list: refactoring, custom machines, block ops, barriers, status effects, skill expansion, player config DB, persistence, shop, EnderChest, rewards, skins, custom currency, K8s, HUD, WorldGuard, tutorial, i18n |

### RTM external plugin dependencies (from `build.gradle.kts`)

| Plugin | Version | Required in RTM | Astera strategy |
|---|---|---|---|
| WeaponMechanics | 3.3.0 | yes (primary combat) | self-built `IProjectileService` + `GunSpec` (ADR-0015 D) |
| Oraxen | 1.170.0 | yes | self-built `IItemRegistry` default + optional Oraxen provider Phase 4 |
| ModelEngine | R4.0.4 | yes | Phase 5/6 ADR comparison (BetterModel vs ModelEngine) |
| MythicMobs | 5.3.5 | yes | self-built mob API (Phase 5+ re-evaluation) |
| ProtocolLib | 5.1.0 | yes | PacketEvents when needed Phase 3+ |
| WorldGuard | 7.0.9 | yes | self-built `Plot`+`Region` domain Phase 5 |
| PlaceholderAPI | 2.11.5 | yes | `MessageKey` + placeholder Map ✅ Wave 1 |
| FastAsyncWorldEdit | (latest) | yes | Paper `WorldCreator` + self-built schematic loader Phase 3 |
| Scoreboard Library | 2.1.3 | yes | `IScoreboard` port + Paper impl Wave 5b + 6 |
| Inventory Framework | 0.10.13 | yes | `IGuiAdapter` port ✅ Wave 4 + Paper impl ✅ |
| PacketEvents | 2.6.0 | yes | When needed Phase 3+ |
| KAML | 0.61.0 | yes | ✅ Wave 1 (kotlinx.serialization + kaml) |
| kotlinx-serialization-json | 1.7.3 | yes | ✅ Wave 1 (1.9.0) |

All 11 required RTM plugins resolved per [[adr/0015-external-plugin-interrogation]]
governance (Decline = 8, Compare-at-Phase = 2, Adopt = 1).

### Persistence

RTM uses **MongoDB (permanent) + Redis (temporary)**; Astera replaced with
**PostgreSQL + Exposed + JSONB** per ADR-0005 and uses Redis as a
fan-out channel only (ADR-0025 broadcaster split). The RTM `:modules:repositories`
shape (split permanent / temporary API + impl) influenced Astera's similar
priority-overlay pattern in `plugin/persistence` (ADR-0020).

## Comparison: `rtm-divergence.md` (pre-empirical) vs this catalog

`docs/architecture/rtm-divergence.md` (Astera-curated, written before this
ADR) captured ~85% of the RTM design surface but missed:

1. **5 of 6 game modes** (only Break Core acknowledged via "BreakCore extension")
2. **Block durability + NBT pseudo-recovery** mechanic (`BlockState.md` not surfaced)
3. **Barrier system specifics** (DisplayEntity visual layer)
4. **Redstone dispenser skill core machine** concept
5. **Reward 2-server architecture** (game-server → Redis → lobby-server distribution)
6. **JP regulatory currency compliance constraints** (6mo / ¥10M cap)
7. **CEL expression language** for skill extensibility
8. **Tutorial system** as a domain
9. **Personal settings DB** as a domain

`rtm-divergence.md` has been updated (same commit as this ADR) to absorb
these additions into the Keep / Redesign / Add tables.

## Deep audit (2026-05-19): source-code + issue-body saturation

**Trigger**: Wave 5b close audit on 2026-05-19. User's instinct ("Phase 1
が薄すぎないか") prompted a second-pass investigation that went deeper than
the 2026-05-17 catalog: source-code enum surveys (`GameMode.kt` /
`AnomalyType.kt` / `SkillTrigger.kt` / `IGamePlayer.kt` / `LevelUpMission.kt`),
issue **body** TODOs (not just titles — 20 of 22 issues carry checkbox
sub-TODOs), Writerside topic full reads, and `paper-plugin.yml` /
`SkillCommandServiceImpl.kt` command surface inspection.

### Newly-discovered Phase 1 items (~15)

These are first-class RTM features missing from the parity tracker as of
2026-05-19; they sit upstream of Wave 5a-6b scope decisions.

| # | Feature | RTM source | Suggested Astera wave |
|---|---|---|---|
| 1 | **`DamageHistoryService`** (sliding-window damage attribution) | `core/impl/.../game/damage/DamageHistoryService.kt` + `DamageHistoryData.kt` + `ConfiguredDamageHistoryParameter` | **Wave 5a-fix2** |
| 2 | **`assist` counter on player session** | `IGamePlayer.kt` — `ITeamGamePlayer.assist: Int` | **Wave 5a-fix2** |
| 3 | **`SkillTrigger` enum 6 → 20** (RIGHT_CLICK_BLOCK / LEFT_CLICK_BLOCK / RIGHT_CLICK_AIR / LEFT_CLICK_AIR / INTERACT / SHOOT / BLOCK_BREAK / CONSUME / CROUCH / JUMP / SPRINT / DROP / STACK_DROP / PICKUP / HYPER / ATTACK / SHIELD / DAMAGED / DEATH / SYSTEM) | `model/skill/SkillTrigger.kt` | **Wave 5a-fix2** (enum-only; Bukkit handlers Wave 6) |
| 4 | **`LevelUpMission`** (per-level KILL vs STAR choice) + `ConfiguredLevelParameter.normaParameter: Map<Int, ConfiguredNormaParameter>` | `LevelUpMission.kt` | **Wave 5e** |
| 5 | **7th game mode `BattleRoyale`** | `GameMode.kt` (see §"7 confirmed game modes" above) | **Wave 7a** (content fidelity) |
| 6 | **3rd TNT variant `CarryBigTnt` (payload)** | `GameMode.kt` (see above) | **Wave 7a** (content fidelity) |
| 7 | **`ItemGenerator`** (non-star item-spawning generator) | `ConfiguredItemGenerator.kt` (alongside `ConfiguredStarGenerator.kt`) | **Wave 7a** (content) |
| 8 | **`PlayerPin`** (small/medium/large team-comms markers) | `ConfiguredPlayerPin.kt` | **Wave 5e** |
| 9 | **`StructureService`** (WorldEdit-style runtime structure paste, distinct from schematic load) | `paper/.../structure/StructureService.kt` | **Wave 6b** (Paper-side runtime ops) |
| 10 | **`PlayerPortalReadyEvent`** (lobby↔match server entry/exit signal) | `event/data/PlayerPortalReadyEvent.kt` | **Wave 5e** |
| 11 | **Control scheme PersonalSettings** (右クリック式 / ボタン式) | README §プレイヤーデータ | **Wave 5e** |
| 12 | **Particle-hide visibility setting** (per-viewer team filter) | README §戦闘システム + §プレイヤーデータ | **Wave 5e** (domain) + **Wave 6a** (Paper filter) |
| 13 | **Double-jump mechanic** (default movement, with `JUMP_DISABLED` debuff opposite) | README §戦闘システム | **Wave 5e** (domain) + **Wave 6a** (Paper packet) |
| 14 | **QuickCast hotbar mapping** (instant skill cast via hotbar number) | Issue #15 body checkbox | **Wave 5e** (PersonalSettings field) |
| 15 | **Vanilla Potion ↔ custom status unified API** | Issue #2 body bullets | **Wave 6** (Paper integration boundary) |

### Newly-discovered Phase 4+ backlog items (~13)

These are speculative content-idea entries from Writerside
`コンテンツ案一覧.md` + issue #12 (寄付系) sub-bullets — Astera adds rather
than ports.

| # | Feature | RTM source | Category |
|---|---|---|---|
| 1 | Sound effect cosmetics (per-kill / respawn / victory / join) | Issue #12 sub-item | Cosmetics (Wave 8e) |
| 2 | Particle cosmetics beyond standard (kill / respawn / victory / join overlays) | Issue #12 sub-item | Cosmetics (Wave 8e) |
| 3 | Nickname custom display + chat color codes | Issue #12 sub-items | Cosmetics (Wave 8e) |
| 4 | Private game mode (exclusive lobby for selected players) | Issue #12 sub-item | Match queue (Wave 8e or new) |
| 5 | Ranked matchmaking (ELO/TrueSkill) | Writerside `コンテンツ案一覧.md` | Match queue (Wave 7b extension) |
| 6 | Tournament automation (bracket / round scheduling) | Writerside `コンテンツ案一覧.md` | Match queue (Wave 7b extension) |
| 7 | Betting 1v1 mode (賭け1v1) | Writerside `コンテンツ案一覧.md` | Match queue (Phase 4+) |
| 8 | Quick spell assist macro (クイックスペルアシスト) | Writerside `コンテンツ案一覧.md` | Authoring UX (Phase 5+) |
| 9 | Skill tagging from CEL grammar (自作言語タグ付け) | Writerside `コンテンツ案一覧.md` | Authoring UX (Wave 5f + Wave 10e integration) |
| 10 | GitHub→Discord patch note auto-notification | Writerside `コンテンツ案一覧.md` | Ops (Wave _ops-8 new) |
| 11 | Vector search / embedding subscription | Issue #12 sub-item | Speculative infra (Phase 5+ research) |
| 12 | 分散型ツリー型ホスティング (distributed tree-based world hosting) | Writerside `コンテンツ案一覧.md` | Speculative infra (Phase 5+ research) |
| 13 | Shop NPC implementation decision (MythicMobs/Citizens) | Issue #4 sub-bullets | Wave 5e/Phase 4 governance ADR |

### Astera-intentional divergences (not gaps)

Per ADR-0013 (RTM divergence policy), Astera intentionally **diverged** on
the following; they are listed here for clarity:

- **6 Anomaly variants `ResourceSpike` / `StatusBurst` / `SpawnSurge` /
  `TimeWarp` / `GravityShift` / `FogOfWar`** (Astera Wave 5c) vs RTM's 4
  `Meteor` / `Freeze` / `Thunder` / `Rain` (`AnomalyType.kt`). Astera's
  abstraction-level naming buys better composability for ADR-0058's modifier
  surface; RTM's are concrete weather/event theming. Both are valid; Astera
  ships the framework Wave 5c and content Wave 7a may layer RTM-flavored
  themes on top.

### Audit methodology (reproducibility)

For future contributors who want to re-run the saturation:

```bash
# Issue body deep-scan
gh api 'repos/Y-RyuZU/RyuZUTechnicalMagic/issues?state=all&per_page=100' \
  --paginate --jq '.[] | {n: .number, title, body}'

# Source-code enum surveys
gh api repos/Y-RyuZU/RyuZUTechnicalMagic/contents/modules/api/core/src/main/kotlin/dev/ryuzu/rtm/api/core/game/mode/GameMode.kt
gh api repos/Y-RyuZU/RyuZUTechnicalMagic/contents/modules/api/core/src/main/kotlin/dev/ryuzu/rtm/api/core/game/player/IGamePlayer.kt
gh api repos/Y-RyuZU/RyuZUTechnicalMagic/contents/modules/api/core/src/main/kotlin/dev/ryuzu/rtm/api/core/model/skill/SkillTrigger.kt
gh api repos/Y-RyuZU/RyuZUTechnicalMagic/contents/modules/api/core/src/main/kotlin/dev/ryuzu/rtm/api/core/game/level/LevelUpMission.kt
gh api repos/Y-RyuZU/RyuZUTechnicalMagic/contents/modules/api/core/src/main/kotlin/dev/ryuzu/rtm/api/core/game/stage/anomaly/AnomalyType.kt

# Writerside topic reads
gh api repos/Y-RyuZU/RyuZUTechnicalMagic/contents/docs/Writerside/topics/タスク.md
gh api repos/Y-RyuZU/RyuZUTechnicalMagic/contents/docs/Writerside/topics/コンテンツ案一覧.md
gh api repos/Y-RyuZU/RyuZUTechnicalMagic/contents/docs/Writerside/topics/BlockState.md
gh api repos/Y-RyuZU/RyuZUTechnicalMagic/contents/docs/Writerside/topics/アイテム管理に関して.md
```

The 2026-05-19 saturation pass found ~15 Phase 1 gaps + ~13 Phase 4+
backlog items. Any subsequent re-audit should target a saturation
delta < 5 items; if more is found, the current methodology is missing
a class of source.

## Consequences

### Positive

- Empirical reference replaces speculation. Future "does RTM have X?"
  questions consult this ADR rather than re-fetching.
- The [[../RTM-PARITY-TRACKER]] master document can cite this ADR as the
  authority for "what RTM has" entries.
- `rtm-divergence.md`'s gaps are now documented and closed.
- ADR-0028 (Roadmap Realignment) uses these findings to expand Wave 5c
  scope (6 game modes) and add Wave 5d / 5e / 5f sub-waves for
  newly-surfaced RTM mechanics.

### Negative / Trade-offs

- This ADR is empirical at a point in time (2026-05-17). RTM may evolve
  (or stagnate). A future contributor noticing drift should re-fetch and
  open an addendum ADR (not edit this one).
- Astera now has more visible RTM-derived scope to land (the 9 missed
  items above), which extends the Phase 3 plan.

### Mitigations

- The parity tracker cross-references this ADR per row so drift is
  detectable.
- Each Wave 5c-5f sub-wave handoff doc cites the specific RTM issue
  number(s) it implements, so re-checking against RTM upstream is
  ~5 minutes per wave.

## Alternatives Considered

- **Don't bother documenting** (treat RTM as a known-unknown): rejected
  because the audit just proved the curated-extract approach missed 9
  significant features.
- **Clone RTM locally + run our own grep**: rejected because RTM is
  Kotlin code we don't ship; we only need the design surface (issues +
  README + Writerside).
- **Wait until RTM's master branch settles**: RTM last touched Nov 2024
  and may not move; waiting blocks Astera indefinitely.

## References

- [[0001-clean-architecture-with-mc-as-edge]]
- [[0002-vendor-neutral-mc-adapter-layer]]
- [[0013-rtm-divergence-policy]] — original policy (Accepted)
- [[0027-phase-1-poc-closing-scope]] — Wave 4.5 closing (Draft)
- [[0028-roadmap-realignment-paper-as-edge]] — uses this catalog (Draft)
- [[../architecture/rtm-divergence]] — updated alongside this ADR
- [[../RTM-PARITY-TRACKER]] — master tracker derived from this catalog
- RTM: https://github.com/Y-RyuZU/RyuZUTechnicalMagic
- RTM issues: https://github.com/Y-RyuZU/RyuZUTechnicalMagic/issues
- RTM Writerside docs: https://github.com/Y-RyuZU/RyuZUTechnicalMagic/tree/master/docs/Writerside/topics
