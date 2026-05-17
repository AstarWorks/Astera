---
status: Draft
date: 2026-05-17
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0028: Roadmap Realignment — Paper-as-edge Re-affirmation + Phase 3 Domain Ordering

## Status

Draft

## Context

Wave 4.5 (commit `68cef2e`, "Phase 1 PoC closing") landed Shop + Loadout
persistence + Norma persistence + SpawnPoint + Phase auto-timer +
IGuiBindingResolver via 6-worker parallel session. Two issues surfaced
in the post-Wave-4.5 review:

### Issue 1: Original Wave 5 framing was wrong

The pre-Wave-4.5 `wave-5-handoff.md` plan put **Wave 5 = R4 Paper runtime**
(PaperDamageGateway / PaperStatusEffectContainer / PaperProjectileService /
BukkitEventAdapter extensions / Cooldown scheduler wire / IBroadcaster
Redis+Discord stub). This violates the Astera architecture's two foundational
ADRs:

- [ADR-0001 (Clean Architecture with MC as edge)](0001-clean-architecture-with-mc-as-edge.md):
  "Minecraft is an edge, not the core domain"
- [ADR-0002 (Vendor-neutral MC adapter layer)](0002-vendor-neutral-mc-adapter-layer.md):
  "adapter/api is vendor-neutral; only adapter/paper may import Bukkit"

The principle: **Paper is the LAST layer to land**, not the next one.
Domain + application + ports + Fake adapters come first. Wave 1-4.5 built
substantial infrastructure but **the Phase 3 Siege Warfare core game
systems are largely unbuilt in domain layer**, so going to Paper next
means we'd write Paper impls against unstable / incomplete contracts.

User catch (paraphrased): "Paper は最後の最後なんでしょ？まだまだ中核の
コア近くで作らないといけないものが多いでしょ？" — and they're right.

### Issue 2: RTM empirical audit was sloppy

The Wave 4.5 retrospective audit assumed RTM (RyuZUTechnicalMagic) was
closed-source without verification, and consequently relied only on
Astera's pre-existing curated extract (`docs/architecture/rtm-divergence.md`).

User catch (paraphrased): "そこサボらずに読めよ。サブエージェントに任せる
とかでもいいからさ" — and they were right. A second-pass audit (WebFetch via
sub-agent) discovered RTM is **fully public**. The catalog of empirical
findings is recorded in [ADR-0029 (RTM Empirical Catalog)](0029-rtm-empirical-catalog.md);
it surfaces **9 RTM features the curated extract had missed**, including
5 of 6 game modes, block durability + NBT pseudo-recovery, the Barrier
system, the Reward 2-server flow, JP regulatory currency compliance, and
CEL expression language.

### Result

Combining these two issues: the original Wave 5 plan was both
mis-prioritized (Paper before domain) AND scoped against incomplete data
(missing 9 RTM features). This ADR records the corrected roadmap.

## Decision

**Reclassify the roadmap so Paper-impl work is deferred to a single
Paper Sweep wave (Wave 6) AFTER all Phase 3 Siege Warfare domain +
application + port + Fake adapter scaffolding is complete.**

### New wave sequence

| Wave | Scope | Layer | Worker count | Effort | ADR |
|---|---|---|---|---|---|
| **Wave 5a** | Combat depth: SkillCast 6 archetype handlers, Armor domain, DamageRule depth (armor reduction / crit / knockback / friendly-fire), Skill resource cost, Charged skill variant | domain + application + ports | 6 | 1 session (~5-6h) | 0030 |
| **Wave 5b** | HUD ports: `IBossBar` / `IScoreboard` / `ITabList` / `IMcPlayer.sendActionBar` / `sendTitle` + Fake impls + CT/star/level layout | adapter-api + Fake | 4-5 | 1 session (~3-4h) | 0031 |
| **Wave 5c** | Game mode framework + 6 game modes (Battle Loyal / Break Core / Carry TNT / Carry Mini TNT / Capture Wool / Steal Jewelry) + Star Generator + Anomaly + sudden-death mechanics | domain + application | 5-6 | 1 session (~5-6h) | 0032 |
| **Wave 5d** | Stage geometry: Block tracking + Block durability (max 100 HP + NBT pseudo-recovery) + Barrier system + Custom machines + Capture zone + Schematic spec + Per-match world domain | domain + application + ports | 6-7 | 1-2 sessions (~6-7h) | 0033 |
| **Wave 5e** | Session + Social: Spectator mode + Friend / Party + Achievement / Title / Badge + Tutorial system + EnderChest (`IPersonalVaultService`) + Personal settings DB + Reward 2-server flow + Audit log | domain + application | 5-6 | 1 session (~5-6h) | 0034 |
| **Wave 5f** | Marketplace + creator revenue + JP regulatory currency compliance (`CurrencyPolicy`) + CEL expression for skill + Particle definition system + Reactive GUI binding extension (`ResolvedReactiveSlot`) | domain + application | 4-5 | 1 session (~4-5h) | 0035 |
| **Wave 6 Paper Sweep** | All Paper impls in one cohesive sprint: PaperDamageGateway + PaperStatusEffectContainer + tick loop + PaperProjectileService (5 motion variants) + PaperWorldGateway (full) + PaperBossBar + PaperScoreboard + PaperTabList + PaperSchematicLoader (FAWE) + PaperSpectator + BukkitEventAdapter extensions + IBroadcaster Redis/Discord real impl + Vec3 math | adapter-paper | 8+ | 1-2 sessions | 0026 |

(Wave _ops, Wave _assets parallel-track items unchanged.)

### Why this order

1. **Paper-as-edge principle (ADR-0001 / ADR-0002) honored end-to-end** —
   no Paper impl lands before its contract is stable.
2. **Each Wave 5a-5f session is independently unit-testable** with FakeXxx
   adapters (`FakeWorldGateway`, `FakeMcServer`, etc.). No Bukkit / Paper /
   MC server needed to verify.
3. **Wave 6 Paper Sweep has full domain/application contracts to bind
   against**, eliminating the in-flight-domain-churn risk that breaks
   Paper impls. All Bukkit code lands in one cohesive sprint, clustering
   the "what if Paper API X behaves unexpectedly" debugging surface.
4. **The audit's "SkillCast has zero subscribers" hollow PoC problem is
   solved in Wave 5a**, not deferred behind Paper work.
5. **6 game modes** (5 of which were missing from `rtm-divergence.md`)
   land cleanly in Wave 5c with a proper `GameMode` framework, rather
   than being retrofitted into a Paper-bound match flow.
6. **RTM parity progression** becomes legible:
   - Wave 4.5: ~15%
   - Wave 5a-5f: ~45%
   - Wave 6: ~65% (Phase 1 PoC end-to-end)
   - Phase 3 + content: ~75%
   - Phase 4: ~85%

### Deprecation of old `wave-5-handoff.md`

The previous `/home/node/.claude/plans/wave-5-handoff.md` describes Wave 5 =
Paper runtime. This document is renamed to `wave-5-handoff.deprecated.md`
with a redirect note pointing at this ADR + the new Wave 5a-5f / Wave 6
sub-wave handoff docs.

### ADR-0026 timing shift

ADR-0026 (Paper Runtime Topology) was previously reserved for "Wave 5"
drafting. It now drafts during **Wave 6 Paper Sweep** instead. Number
remains reserved.

## Consequences

### Positive

- Paper-as-edge principle (ADR-0001 / ADR-0002) honored end-to-end. Paper
  impls bind against stable contracts.
- Wave 6 Paper Sweep has full domain/application contracts to bind
  against; no in-flight domain churn during Paper impl. Reduces the
  surface area of "what if Paper API X behaves unexpectedly" debugging
  by clustering it.
- RTM parity arrives in 2 cohesive jumps (Wave 5a-f then Wave 6) rather
  than trickled across many partial implementations.
- Each Wave 5a-5f session is self-contained and unit-testable (FakeXxx
  adapters cover the gap until Wave 6).
- Skill dispatch ("SkillCast has zero subscribers" — the hollow Wave 4.5
  PoC problem) is solved in **Wave 5a**, not deferred behind Paper work.
- The 6 RTM game modes land in a proper `GameMode` framework (Wave 5c),
  not retrofitted into a Paper-bound match flow.

### Negative / Trade-offs

- "1 weapon fires" demo is delayed from next session (was: Wave 5 Paper)
  to after Wave 6 Paper Sweep — ~4-5 sessions out instead of 1.
- Wave 6 is a larger session than typical Astera waves (8+ workers vs
  6 max validated by Wave 1-4.5).
- Demo / playtest velocity is lower until Wave 6 lands. Until then,
  domain + unit tests are the only verification surface.

### Mitigations

- Each Wave 5a-5f session ends with a parity-tracker update
  ([[../RTM-PARITY-TRACKER]]), so progress is visible and measurable.
- Wave 6 can be split into Wave 6a (combat Paper: damage / projectile /
  status) and Wave 6b (HUD / world / broadcaster) if the single-sprint
  scope proves too large at planning time.
- The "no playable demo for 4-5 sessions" risk is acknowledged. The
  alternative (Paper-first) trades it for "playable demo now but unstable
  contracts" — which is the trade Wave 4.5 audit already rejected.

### Operational

- 6 new sub-wave handoff docs land in `/home/node/.claude/plans/` (out
  of repo) with this commit.
- `radius-roadmap-handoff.md` is updated to reflect the new wave
  sequence.
- Old `wave-5-handoff.md` is renamed to `.deprecated.md` with a redirect
  note.
- ADR numbers 0030-0035 are reserved for the 6 sub-waves.

## Alternatives Considered

- **Path A: Proceed with Wave 5 = Paper PoC as originally planned**
  Rejected. Violates ADR-0001 / ADR-0002 principle. Would build Paper
  damage / projectile / status against contracts that are missing armor
  reduction, knockback, skill dispatch, etc. — leading to Paper rework
  every subsequent wave.

- **Path C: Wave 5 split into Wave 4.7 (application) + Wave 5 (Paper)**
  Rejected. Two sessions for the same ordering result; Wave 5a alone
  covers most of what would be Wave 4.7, and labeling makes the Paper-
  as-edge story less clear.

- **Path D: Skip Wave 5 entirely, plan Wave 6 (Phase 3 Siege Warfare)
  directly**. Considered but rejected: Phase 3 Siege needs the Wave 5a-f
  domain work *first* (skill dispatch, armor, game modes, block
  tracking, barriers). Wave 5a-f is exactly that work, just named with
  proper wave granularity instead of one giant pre-Phase-3 wave.

- **Single-pass big Wave 5 (5a-f all in one session)**: rejected. Worker
  parallelism caps at 6-7 per session (Wave 1-3 evidence). Trying 30+
  workers across 6 sub-waves in one shot would multiply merge conflict
  surface and re-create the Wave 3 classloader debug period.

## Relationship to other ADRs

- [ADR-0001 (Clean Architecture with MC as edge)](0001-clean-architecture-with-mc-as-edge.md) —
  this ADR re-affirms the edge principle by restructuring the roadmap
- [ADR-0002 (Vendor-neutral MC adapter layer)](0002-vendor-neutral-mc-adapter-layer.md) —
  same
- [ADR-0013 (RTM divergence policy)](0013-rtm-divergence-policy.md) —
  unchanged; this ADR uses its policy to assign waves
- [ADR-0019 (Unified Tick Scheduler)](0019-unified-tick-scheduler.md) —
  unchanged; Wave 6 uses it for status tick loop + cooldown firing
- [ADR-0020 (Persistence Architecture)](0020-persistence-architecture.md) —
  unchanged; Wave 5d adds 2 more Postgres tables (block tracking,
  barrier) and Wave 5e adds 3-4 more
- [ADR-0021 (Declarative GUI Architecture)](0021-declarative-gui-architecture.md) —
  unchanged; Wave 5b adds `IBossBar` / `IScoreboard` / `ITabList` ports
  in the same vendor-neutral spirit
- [ADR-0022 (Multi-plugin portable architecture)](0022-multi-plugin-portable-architecture.md) —
  unchanged; Wave 5a-f stay within the 7 existing plugins
- [ADR-0025 (Event wiring & Broadcaster/EventBus split)](0025-event-wiring-broadcaster-eventbus-split.md) —
  unchanged; Wave 5a fills SkillCast subscribers; Wave 6 extends
  IBroadcaster fan-out
- [ADR-0027 (Phase 1 PoC closing scope)](0027-phase-1-poc-closing-scope.md) —
  this ADR explicitly supersedes ADR-0027's "Wave 5 = Paper next"
  assumption; Phase 1 PoC closing is now Wave 5a + Wave 6 minimum, not
  Wave 4.5 + Wave 5 Paper
- [ADR-0029 (RTM Empirical Catalog)](0029-rtm-empirical-catalog.md) —
  this ADR uses its empirical findings to assign Wave 5c (6 game modes),
  Wave 5d (block durability + barrier), Wave 5e (Reward 2-server +
  EnderChest + Tutorial + Personal settings), Wave 5f (JP currency +
  CEL)
- [ADR-0026 (Paper Runtime Topology)](#) — RESERVED → drafts during
  Wave 6 Paper Sweep (NOT Wave 5 as previously planned)

## Notes

- Sub-wave handoff docs (`wave-5a-handoff.md` through `wave-5f-handoff.md` +
  `wave-6-handoff.md`) accompany this ADR in `/home/node/.claude/plans/`.
- `wave-5-handoff.md` deprecated; redirect note added.
- [docs/RTM-PARITY-TRACKER.md](../RTM-PARITY-TRACKER.md) is the living
  master tracker; each Wave 5x / Wave 6 session updates it as part of
  its Phase C integration.
