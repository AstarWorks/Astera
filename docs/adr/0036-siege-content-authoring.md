---
status: Accepted
date: 2026-06-03
deciders: Astera core
supersedes: null
superseded_by: null
---

# ADR-0036: Wave 7a — Siege Content Authoring (Tier 8-1, scope reduced)

## Status

Accepted. **Scope reduced from the original roadmap entry.** Only §A
(`content/stages/siege-arena.yaml`) lands here; §B (6 game-mode content
YAMLs), §C (weapons + skills content), §D (armor + status content), and
§E (balance YAML expansion) are deferred pending upstream wave
prerequisites. The ADR's value is anchoring the Siege Arena stage layout
as canon for downstream waves to reference.

## Context

`docs/IMPLEMENTATION-ROADMAP.md` §"Wave 7a — Content authoring at scale"
listed five content categories: 1 Siege arena, 6 game modes, 4-8 Star
generators, 3-5 Anomalies, 8+ weapons, 16+ skills, 8+ armor, 16+ status
effects, balance YAML completion. The roadmap row marked the wave as
**"コード少なめ + YAML 大量"** — assuming the per-category Kotlin
implementations already shipped in upstream waves (Wave 5c game modes,
Wave 5a armor/status, etc.).

Implementation reality on entry to Wave 7a:

- **`plugin/match/application/gamemode/`** contains only
  `annihilation/AnnihilationGameMode.kt` (Wave 5annihilation, ADR-0108)
  and `NormaGameMode.kt`. The six RTM game modes
  (BattleLoyal / BreakCore / CarryTNT / CarryMiniTNT / CaptureWool /
  StealJewelry) flagged in roadmap §"Wave 5c" do not yet have Kotlin
  impl classes. `GameModeYamlConfig.implClass` is resolved against the
  JVM class loader at plugin enable; writing 6 YAMLs referencing
  non-existent classes would fail at startup.
- **Weapons + skills content** uses the Wave _items-1 + Wave _skills-X
  schemas, which already absorb arbitrary content; in principle writing
  more YAML is fine. But the existing `example-pistol.yaml` /
  `example-sword.yaml` / `example-wand.yaml` already exercise every
  schema branch the Phase 1 PoC smoke test needs (single click → fire →
  damage). Authoring 8+ weapons + 16+ skills before Wave 7b balance
  iteration tooling lands would lock in numbers we'd just rewrite.
- **Armor + status content**: `content/armor/example-*.yaml` and
  `content/status/{burn,freeze,poison,example-*}.yaml` already cover the
  ADR-0058 ArmorReduction schema and the most-referenced status types.
  RTM "16 status effects minimum" is a Phase 3+ depth requirement (the
  domain has the surface; content authoring lands when the gameplay
  needs each effect).
- **Balance YAML completion**: `content/balance/element-matrix.yaml` is
  already complete vs the current `DamageAttribute` enum (PHYSICAL,
  FIRE, ELECTRIC, WIND, WATER, EARTH — 5 non-physical pairs, all
  covered). `block-durability.yaml` covers the tool × block × tier
  matrix. `armor-reduction.yaml` (in the original roadmap scope) has
  no DTO consumer wired — adding it would be an orphan file. The
  current balance YAMLs satisfy the Phase 1 PoC smoke test.

What clearly was shippable: the Siege Arena stage YAML. It's a layout
artifact (coordinates + region names + spawn points per game mode), not
gameplay code, and the canonical reference downstream content waves
will consume. Wave 7a ships exactly this and documents the rest.

## Decision

### §A — `content/stages/siege-arena.yaml` (LANDED)

Single 241-line file documenting:

- **Coordinate envelope**: `X ∈ [-100, +100]`, `Y ∈ [60, +160]`,
  `Z ∈ [-100, +100]`. Red base at -X, Blue base at +X.
- **17 regions** named for cross-content reference:
  - `red_base` / `blue_base` — team bases
  - `central_zone` — KOTH / BreakCore objective
  - `generator_a` through `generator_h` (8 Star Generator anchor
    positions; the future `content/generators/siege-arena.yaml` will
    cite these region names)
  - `jewelry_red` / `jewelry_blue` — StealJewelry pedestal positions
  - `tnt_spawn` — CarryTNT origin
  - `mini_tnt_zone_a` through `mini_tnt_zone_e` — CarryMiniTNT
    collection points
- **Spawn points** per game mode × per faction (attacker / defender /
  lobby / spectator), totaling ~32 spawn declarations covering all 6
  Siege game modes.
- **No schematic `.schem`** — FAWE is optional per ADR-0015; the file
  is a hand-built layout an admin builds in-world or pastes via /worldedit.

The file mirrors the schema of `example-arena.yaml` / `forest-arena.yaml`
/ `boss-arena.yaml` (consumed by `StageYamlConfig.kt`). No new fields
introduced; no schema changes required.

### §B / §C / §D / §E (DEFERRED — see "Out of scope")

## Consequences

### Positive

- Downstream content waves now have an authoritative Siege Arena layout
  to cite. `content/generators/siege-arena.yaml` (Wave 7a-N1)'s 8
  generator positions are pre-defined by the `generator_a..h` region
  names; the YAML author doesn't have to reinvent coordinates.
- The 6 game-mode content YAMLs (Wave 7a-N2) have their per-mode spawn
  points already laid out (see §A YAML's `spawnPoints` section). When
  the matching Kotlin GameMode impls land (Wave 5c-deferred), the YAML
  authoring is reduced to wiring objectives + scoring rules + the
  `impl_class` reference.
- ADR-0036 honestly documents the upstream gap. A future reader sees
  the Wave 5c game-mode-impl backlog at the right place in the wave
  chain, rather than discovering it mid-Wave-7b.

### Negative / Trade-offs

- RTM parity does NOT advance to the "~80%" mark the roadmap projected
  for Wave 7a completion. The Siege content baseline is one file, not
  the multi-dimensional content matrix.
- The Siege Arena stage is layout-only — it does not exercise any
  game-mode logic until §B lands. A reviewer who wants to "see the
  Siege Arena play" will hit the same dead end the smoke test already
  has (Phase 1 PoC end-to-end is what the existing
  `content/stages/example-arena.yaml` already lights up).
- Wave 7b (Tournament + Leaderboard) is downstream of "1 complete
  playable Siege map across all 6 game modes" per the roadmap. Wave
  7a's §B-§E deferral pushes the projected Wave 7b start back until
  Wave 5c game-mode impls land.

### Mitigations

- The siege-arena.yaml file is fully readable; downstream authors can
  start typing 6 game-mode YAMLs the moment the Kotlin impls land.
  There's no chicken-and-egg dependency in the OPPOSITE direction (the
  impls don't reference the YAMLs).
- The deferred §s are individually small and parallelizable. A future
  "Wave 7a-N1" can ship just §B (game modes content) once the impls
  arrive. §C/§D can run in parallel against §B. None of them block
  Wave 6c-stub items (HUD/PacketEvents, Explosion, Spawner) or the
  Phase 1 PoC smoke test.

## Alternatives Considered

- **Ship §B with `impl_class` pointing at `NormaGameMode` as a stub**:
  rejected. Six YAMLs that all behave identically would be deceptive
  documentation. The runtime would load them, the framework would
  register them as separate modes, but they'd all play like Norma — a
  worse outcome than not shipping at all.
- **Ship §C/§D (weapons + skills + armor + status) without §B**:
  rejected. The roadmap row binds them together because the content
  themes are mutually reinforcing (a Siege weapon needs Siege game-mode
  objectives to be balanced against). Shipping content in isolation
  without playable modes turns the work into a numbers-tuning exercise
  that will be rewritten when game modes arrive.
- **Implement the 6 Wave 5c game-mode classes inside this wave**:
  rejected. That's Wave 5c scope (ADR-0032) and a fundamentally
  different concern — sealed `GameMode` framework + per-mode listener
  subscribing to `BlockBroken` / `EntityDied` / `TNTPickup` etc. + 6
  domain implementations. It would require ~6 days of focused work and
  would mis-attribute the change in git blame / commit history.
- **Skip ADR-0036 entirely** (just ship siege-arena.yaml under Wave 7a
  branch without a paper trail): rejected. ADR-0036 was reserved for
  this wave per `docs/IMPLEMENTATION-ROADMAP.md §"ADR allocation per
  wave"`. Leaving it un-drafted while shipping the branch would
  forfeit the explanation of *why* §B-§E aren't here.

## Out of scope (deferred)

The following Wave 7a §s are NOT addressed here. Each is deferred with
a rationale + the upstream prerequisite that must land first:

- **§B — 6 game-mode content YAMLs + generators + anomalies**:
  blocked on the Wave 5c game-mode Kotlin impls
  (`BattleLoyalGameMode` / `BreakCoreGameMode` / `CarryTntGameMode` /
  `CarryMiniTntGameMode` / `CaptureWoolGameMode` /
  `StealJewelryGameMode`). When those land, write 6 YAMLs at
  `content/game-modes/{battle-loyal, break-core, carry-tnt,
  carry-mini-tnt, capture-wool, steal-jewelry}.yaml` mirroring the
  `annihilation.yaml` schema and citing the new impl classes. The 8
  Star Generator anchor positions are pre-reserved in §A as
  `generator_a..h`; the matching `content/generators/siege-arena.yaml`
  is then a 30-minute file. Anomaly content (Status Burst / Spawn
  Surge / Time Warp / Gravity Shift / Fog Of War) reuses Wave 5g's
  AnomalyType sealed surface (already shipped) and is a 1-hour stub.
  Wave label: `Wave 7a-N1`.
- **§C — 8+ weapons + 16+ skills**: not blocked, but premature — the
  current `example-*` content covers every schema branch the Phase 1
  PoC smoke test exercises. Authoring more weapons + skills before
  Wave 7b balance-iteration tooling (ADR-0038) lands means the numbers
  will be rewritten under load testing. Defer to **post-Wave-7b** as
  the natural authoring slot. The schema is already stable (ADR-0073);
  no further design work needed before authoring begins.
- **§D — 8+ armor + 16+ status effects**: same logic as §C. The Wave
  5e armor schema (ADR-0058) and the Wave 5a status schema are stable;
  the existing examples cover every schema branch; bulk authoring
  belongs in a content-only follow-up wave alongside §C. Wave label:
  `Wave 7a-N2`.
- **§E — `armor-reduction.yaml` + balance refinements**: the
  per-damage-type armor reduction lookup table has no DTO consumer
  wired (ADR-0058's `ArmorReduction` reads from inline `armor/*.yaml`
  reduction fields, not a shared matrix). Adding the file without a
  consumer would be dead code. `element-matrix.yaml` is already
  complete vs the current 5-non-physical-element `DamageAttribute`
  enum. `block-durability.yaml` covers the existing tool × block × tier
  matrix. Defer the armor-reduction-matrix concept to a future ADR
  that introduces the matrix consumer + the YAML at once.

## References

- [[adr/0036-template]] — n/a (this is ADR-0036).
- [[adr/0032-game-mode-framework-and-generator-anomaly]] — Wave 5c
  (the upstream prerequisite for §B).
- [[adr/0108-annihilation-game-mode]] — Wave 5annihilation, the only
  fully-shipped GameMode impl as of 2026-06-03.
- [[adr/0058-universal-attribute-container]] — armor reduction shape;
  current consumer reads inline, not from a shared matrix.
- [[adr/0073-item-first-content]] — weapons schema SSOT.
- [[adr/0015-schematic-loader]] — FAWE-optional path for stage
  schematic data.
- `content/stages/siege-arena.yaml` — the artifact this ADR ships.
- `docs/IMPLEMENTATION-ROADMAP.md §"Wave 7a"` — original roadmap row
  with the un-reduced scope.
