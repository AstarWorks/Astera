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

### 6 confirmed game modes (per RTM README)

| # | Mode | Goal |
|---|---|---|
| 1 | **Battle Loyal** | Small-scale gathered-item arena |
| 2 | **Break Core** (primary intended mode) | Destroy enemy core |
| 3 | **Carry TNT** | Transport TNT to enemy base |
| 4 | **Carry Mini TNT** | Collect 10 mini-TNTs into 1 larger TNT |
| 5 | **Capture Wool** | Area / region control |
| 6 | **Steal Jewelry** | Retrieve 3 enemy gems |

Astera's existing roadmap and `rtm-divergence.md` only mentioned "BreakCore
extension"; the other 5 modes were unaccounted for. They land in
**Wave 5c** as a sealed `GameMode` framework + 6 mode impls.

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
