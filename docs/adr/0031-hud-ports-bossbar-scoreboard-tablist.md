---
status: Draft
date: 2026-05-19
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0031: HUD Ports — BossBar / Scoreboard / TabList / ActionBar / Title

## Status

Draft

## Context

Astera's domain and application layers have, until Wave 5b, produced no
player-facing transient UI beyond chat messages (`IMcPlayer.sendMessage(...)`
and the declarative `IGuiAdapter` for inventory-style windows). The match
flow Wave 5c is about to introduce (game-mode logic, phase transitions,
Star Generator events) needs **moment-to-moment visual feedback** that
chat cannot carry:

- A persistent **bossbar** above the hotbar for "match phase + time remaining"
- A side **scoreboard** for "team scores + objective progress"
- An **action bar** (the single line above the hotbar) for "current objective hint"
- A **title** splash for phase transitions ("MATCH START", "MATCH END")
- A **tablist** header/footer for "server info, your team, your ping"

RTM (the public predecessor at github.com/Y-RyuZU/RyuZUTechnicalMagic) uses
Scoreboard Library 2.1.3 directly from its match code. Astera cannot do
that and stay inside ADR-0001 / ADR-0002 / ADR-0021 (Minecraft as edge,
vendor-neutral adapter API, declarative-port-then-impl): a feature plugin
that holds a `dev.jcsoftware.minecraft.scoreboards.JScoreboard` reference
has bound itself to a specific Paper plugin's API surface.

Wave 5b therefore introduces 5 vendor-neutral HUD ports plus 2
`IMcPlayer` method extensions, registers in-memory defaults at priority 0
in Core, and defers the Paper impls (Adventure API + library-choice ADR)
to Wave 6.

ADR-0058 §6 binds Wave 5b to **also** establish the read-path of the
Universal Attribute Container — HUD widgets must read HP / mana via
`IAttributeContainer.number(Attributes.MAX_HP)` rather than reaching into
`PlayerStats` / `ManaPool` directly. This ADR records that the
`ShimPlayerAttributeContainer` (combat plugin, Wave 5b) is that
read-path, with the real migration arriving in Wave 5e per ADR-0058 §5.

## Decision

### 1. Five vendor-neutral ports under `plugin/core/.../adapter/api/hud/`

| Port | Shape |
|------|-------|
| `IBossBar` + `IBossBarManager` | Per-id bar registry + per-player visibility set. Multiple bars per viewer permitted. |
| `IScoreboard` + `IScoreboardManager` | Line-based sidebar (0..14 indices, Minecraft limit). |
| `ITeamScoreboard` (sibling of `IScoreboard`, same manager) | Team-name + score map. Discriminated from `IScoreboard` in the manager so callers don't `instanceof`. |
| `ITabList` | Per-player header / footer / entries. No cross-player broadcast. |
| (`IMcPlayer` extensions) | `sendActionBar(messageKey, placeholders)` + `sendTitle(titleKey, subtitleKey?, fadeIn, stay, fadeOut)`. |

All keys use `MessageKey` (i18n-aware), all player identity uses
`PlayerId`. No `org.bukkit.*` / `io.papermc.*` / `net.kyori.adventure.*`
imports in the port layer (Konsist enforces).

### 2. Production in-memory impls + per-plugin Fakes

- `plugin/core/.../application/service/InMemoryBossBarManager.kt` (+ Scoreboard + TabList) — registered by `AsteraCorePlugin.onEnable` at priority 0. The Wave 6 Paper impl registers at priority 100, supersedes transparently.
- `plugin/test-fixtures/.../FakeBossBarManager.kt` (+ Scoreboard + TabList) — production-quality fakes for cross-plugin tests. JUnit-aware path so Core doesn't carry JUnit on its runtime classpath.

The Fake and InMemory pairs are intentional near-duplicates: keeping
test-fixtures out of Core's runtime classpath is worth ~150 lines of
duplicate code that Wave 6 deletes when the Paper impl lands.

### 3. `IMcPlayer.sendActionBar` / `sendTitle` are interface-level

Not separate ports. Action bars and titles are intrinsically per-player
operations; routing them through a separate `IActionBarService` would be
ceremony with no architectural benefit. The Paper impl stubs are
`TODO("Wave 6 で実装")` so the project compiles in 5b.

### 4. Reference design — CT / Star / Level HUD layout (RTM issue #14)

This is **not implemented in Wave 5b**; it's the design Wave 6 / Wave 5c
target when wiring the ports above. Listed here so the keys reserved in
`content/languages/{en,ja}.yaml` have a documented home.

A Capture-the-Stars match HUD composes the 5 ports as follows:

```
┌───────────────────────────────────────────────────┐
│              [BossBar] Phase: ACTIVE              │
│              Time: 03:42                          │
│                                                   │
│   World view (chat + game world)                  │
│                                                   │
│                                                   │
│         <Title> MATCH START                       │  ← Title splash at
│         <Subtitle> 3… 2… 1…                       │    phase transition
│                                                   │
│                                                   │
│ ┌─────────────────────┐                           │
│ │  Match               │       ← Scoreboard       │
│ │   Red:   12          │         sidebar          │
│ │   Blue:  10          │         (right side)     │
│ │   ────                │                          │
│ │   You: 3 K / 1 D     │                          │
│ │   HP: 18/20          │  ← reads via             │
│ │   Mana: 75/100       │    container.number(...) │
│ └─────────────────────┘                           │
│                                                   │
│  Objective: Capture the Red flag    ← ActionBar   │
│  ▮▮▮▮▮▮▯▯▯▯ [hotbar]                              │
└───────────────────────────────────────────────────┘
```

**Port assignments**:

- **BossBar** (`hud.bossbar.match.phase`, `hud.bossbar.match.time_remaining`)
  — match phase + remaining time. One bar per match.
- **Scoreboard sidebar** (`hud.scoreboard.match.title`) — team scores +
  per-player stats. Lines 0..6 reserved for scores; lines 7..14 for
  per-viewer K/D/HP/Mana.
- **ActionBar** (`hud.actionbar.objective`) — current objective hint,
  refreshed every ~2 seconds because vanilla fades it.
- **Title** (`hud.title.match_start` / `hud.title.match_end`) —
  phase-transition splash. Default fade timings: 500ms / 3500ms / 1000ms.
- **TabList** — per-team grouping + ping. Header is server name + match
  id; footer is "press TAB for full scoreboard" hint.

### 5. Attribute container read-path (ADR-0058 §6)

HUD widgets that need a numeric attribute (HP, mana, attack damage)
read through `IAttributeContainer.number(key)`. Wave 5b ships:

- **Read API** in `plugin/core/.../domain/attribute/`: `AttributeKey`,
  sealed `AttributeValue`, `IAttributeContainer` (read methods —
  Wave 5c added the no-op-default modifier surface on top), `Attributes`
  registry (`MAX_HP`, `MANA_CURRENT`, resists, etc.), `number()` /
  `flag()` extension helpers.
- **Shim** in `plugin/combat/.../application/attribute/`:
  `ShimPlayerAttributeContainer` wraps existing `PlayerStats` + `ManaPool`,
  hardcoding `MAX_HP = 20.0` until Wave 5e introduces real HP state.

Real persistence (V14 `player_attributes` JSONB) + facade rewrites
(`Wallet` / `PlayerStats` / `ManaPool` become read-throughs) land in
Wave 5e per ADR-0058 §5 rollout schedule.

### 6. Wave 6 deferred items

- **Paper impls**: `PaperBossBar`, `PaperScoreboard`, `PaperTabList`,
  Paper `sendActionBar` / `sendTitle` (Adventure API). Registered at
  priority 100 in `AsteraCorePlugin.onEnable`'s Paper-section
  (Wave 6 introduces it).
- **Scoreboard library choice**: Scoreboard Library 2.1.3 vs direct
  Adventure team API vs hand-rolled packet sending. Comparison + ADR in
  Wave 6 once the Paper impl prototype runs.
- **`MessageKey` placeholder rendering**: Wave 5b in-memory impls do
  naive `{name}` substitution; Wave 6 routes through
  `IMessageRenderer` so each viewer's locale is honored.

## Consequences

### Positive

- **Vendor-neutrality preserved**: a Match listener that calls
  `bossBarManager.createBossBar(...)` is unaware whether Adventure,
  ScoreboardLibrary, or a tomorrow's "Folia-region-pinned bossbar"
  impl is downstream. ADR-0001 / 0002 / 0021 hold without exception.
- **Wave 5c can wire HUD today**: Star Generator events,
  game-mode-listener phase transitions, and tutorial scripts can call
  the ports immediately with the in-memory default; nothing visible
  to clients until Wave 6 swaps the Paper impl in, but unit tests
  cover the call sites.
- **ADR-0058 read-path established**: HUD widgets in 5c/5d/5e never
  see `PlayerStats.kills` etc. directly. The migration in Wave 5e
  changes only the shim's internals.

### Negative / Trade-offs

- **5 new ports widen the public surface** of `plugin:core`. Wave 6
  must implement all 5 simultaneously; a partial Paper impl (e.g.,
  BossBar only) would leave the Match HUD broken in production.
- **Naive `{name}` substitution** in the in-memory impls is not
  i18n-correct (no MiniMessage parsing, no locale lookup). Tests that
  depend on rendered text must understand this limitation.
- **Cross-flavor scoreboard collisions are `IllegalStateException`**.
  A caller that registers `ScoreboardId("match-1")` first as
  line-style and then as team-style fails at the second call. The
  alternative — silently morphing — felt worse.
- **Read-API-only attribute container** with no modifiers means Wave
  5b shim cannot represent equipped-item bonuses or status-effect
  attribute changes. Wave 5c added a default no-op modifier surface
  on the interface; real modifier storage waits for Wave 5e.

### Mitigations

- **Konsist coverage**: new files inherit the existing rules
  (`core domain must not depend on any platform`, `core adapter-api
  MUST stay vendor-neutral`, `application layers must not import raw
  JVM throwable types`, `i18n keys must match across en and ja`).
  Wave 5b adds no new rule but exercises all of these on the new
  packages.
- **In-memory impls track real Paper semantics**: progress range
  `[0, 1]`, sidebar 0..14 indices, single-active-board-per-player,
  TabList per-player isolation. Wave 6 should not need to revisit
  the contract.
- **`TODO("Wave 6 で実装")` in `PaperPlayer.sendActionBar` / `sendTitle`**
  surfaces loudly at runtime if a Paper-built match accidentally
  reaches the Paper impl before Wave 6 lands; tests still pass
  because they go through `FakeMcPlayer`.

## Alternatives Considered

- **Direct Bukkit `BossBar` / `Scoreboard` in match code**: rejected
  by ADR-0001 / ADR-0002 (Minecraft is an edge). Konsist `core
  adapter-api MUST stay vendor-neutral` would fail.
- **Scoreboard Library 2.1.3 as a hard dep right now**: rejected by
  ADR-0009 (soft-dep policy) + ADR-0021 (declarative-port-then-impl
  for player-facing UI). Wave 6 reconsiders after the Paper impl
  prototype identifies the actual ergonomic gap (if any).
- **A single unified `IHudPort` mega-interface**: rejected. BossBar /
  Scoreboard / TabList have orthogonal lifecycles (registry vs
  per-player vs viewer-private), and a unified port would need a
  sealed `HudOperation` discriminator that buys nothing over five
  cohesive small interfaces.
- **Add HUD as a separate `plugin:hud`**: rejected. HUD has no domain
  of its own; it's an adapter surface. Living in `core.adapter.api`
  alongside `IMcPlayer` keeps the dependency graph flat. If a
  per-feature HUD plugin emerges later (e.g., a tournament dashboard
  HUD) it can register its own port impls at priority N.
- **Defer ActionBar / Title to a later wave**: rejected. Tutorial
  (Wave 5e) and damage display (Wave 5a-fix already speaks of it)
  need them, and the surface is two methods on an existing
  interface — adding them now costs nothing.

## References

- [[adr/0001]] Clean Architecture with Minecraft as edge
- [[adr/0002]] Vendor-neutral Minecraft adapter layer
- [[adr/0009]] Soft-dep-only Paper `plugin.yml`
- [[adr/0015]] External plugin interrogation (D-path = build vendor-neutral first)
- [[adr/0021]] Declarative GUI architecture (the template Wave 5b mirrors)
- [[adr/0022]] Multi-plugin portable architecture
- [[adr/0026]] Paper Runtime Topology (Wave 6 — reserved, where Paper HUD impls land)
- [[adr/0028]] Roadmap realignment: Paper as edge
- [[adr/0030]] Combat Depth — sibling Wave 5a-fix
- [[adr/0058]] Universal Attribute Container — Wave 5b establishes the read-path
- [[../IMPLEMENTATION-ROADMAP]] Wave 5b row
- [[../RTM-PARITY-TRACKER]] Category 4 (HUD ports)
- Wave 5b handoff doc (`~/.claude/plans/wave-5b-handoff.md`)
