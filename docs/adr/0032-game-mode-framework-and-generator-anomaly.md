---
status: Proposed
date: 2026-05-19
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0032: Game Mode Framework + Star Generator + Anomaly

## Status

Proposed

## Context

Pre-Wave-5c, Astera's match domain was mode-agnostic: every match transitioned
through `WAITING → COUNTDOWN → ACTIVE → SUDDEN_DEATH → ENDED` and exited via
the score-threshold `VictoryConditionEvaluator`. RTM's empirical catalog
([[adr/0029]]) and the project README both list six concrete game modes
(BattleLoyal / BreakCore / CarryTNT / CarryMiniTNT / CaptureWool /
StealJewelry), none of which had any representation in code. RTM also
references two paired systems for match-pacing dynamics:

- **Star Generator** — BedWars-style resource spawners tied to per-match
  positions, with currency, period, and amount.
- **Anomaly** — random in-match perturbations (tempo, status, visibility,
  movement) that fire on schedule and overlay attribute modifiers.

Both systems need per-instance runtime state (a generator accumulates energy
toward its next spawn; an active anomaly carries a duration + a modifier
shape). Per [[adr/0058]] §5, "new domain in Wave 5c–5f lives in the universal
attribute container natively from day one" — so the Wave 5c design must use
`IAttributeContainer` for that state rather than introduce ad-hoc fields.

A fourth concern is the **Sudden Death** phase. Pre-Wave-5c it was a phase
name with only an auto-timer to ENDED; there was no actual behavioral
acceleration. The handoff scope requires "generator 速度 2x + respawn
disabled + zone shrink" while staying mode-agnostic and uniform with the
Anomaly modifier shape.

This ADR records the structural decisions made by Wave 5c so Wave 5d (world
geometry) and Wave 6 (Paper sweep) can extend the framework without
re-litigating the basics.

## Decision

### 1. Sealed `GameMode` with per-file subclasses + per-mode listener pattern

`com.astarworks.astera.match.domain.mode.GameMode` is a sealed class with
six subclasses, each in its own file (`BattleLoyal.kt`, `BreakCore.kt`, etc).
Mode-specific fields live on the subclass (`BreakCore.coreBlockId`,
`CarryTNT.deliveryRegionIds`, etc.).

For each mode, an `application/usecase/mode/<Mode>Listener` class subscribes
to whichever domain events the mode's win condition reacts to —
`BlockBroken` for BreakCore, `EntityPickedUpItem` + `EntityEnteredRegion` for
the carry/steal modes, etc. The listener publishes `MatchEnded` with the
mode-determined winning team when the condition is met.

Rejected alternative: a single strategy interface (`GameMode.evaluate(match)`)
shared by all modes. Modes have heterogeneous trigger events; cramming them
behind one interface forces every mode to poll match state every tick rather
than react to specific events.

### 2. `IGameModeRegistry` outbound port + in-memory default

The registry is a normal Astera outbound port resolvable via the shared
`ServiceRegistry`. `AsteraMatchPlugin.onEnable` populates the in-memory
default with all six modes at priority 0; a future Wave 6 stage loader can
swap to YAML-driven config without touching the wiring contract.

### 3. Star Generator domain — data class + per-instance `IAttributeContainer`

`StarGenerator` carries `id / matchId / position / currency / period /
amount / tier / affiliation / attributes`. The `attributes:
IAttributeContainer` holds **per-instance runtime state**:

- `generator.energy` — accumulated charge (ticks). Reset on spawn.
- `generator.period_multiplier` — base 1.0; Anomaly and Sudden Death
  overlay MULTIPLY modifiers here.
- `generator.amount_multiplier` — base 1.0; Anomaly overlays here.

`AdvanceGeneratorChargeUseCase` runs once per server tick (via
`StarGeneratorScheduler`), reads the effective period from the container,
and emits `ResourceSpawned` when energy crosses the threshold. The threshold
crossing carries energy overshoot into the next cycle so jitter doesn't
accumulate drift.

**Base-seeding contract**: per [[adr/0058]] §1, `effectiveNumber` returns
`base * Π MULTIPLY`. For modifier-only attributes (no caller stores a base),
the resolved value is `0.0`, which the use case maps to identity (`1.0`)
as a fallback. To make MULTIPLY modifiers from Sudden Death / Anomaly
actually apply, `GeneratorYamlConfig.toDomain` and the equivalent
test-fixture path **seed `PERIOD_MULTIPLIER` and `AMOUNT_MULTIPLIER` to 1.0
at construction**. Documented in both `AdvanceGeneratorChargeUseCase` and
`GeneratorYamlConfig` kdocs.

### 4. Anomaly scheduler — weighted random tick polling

`AnomalyScheduler` runs `IScheduler.runRepeating(period, period)` (default
5 s). Each fire iterates active matches, rolls `random.nextDouble() <
FIRE_PROBABILITY` (0.1), and on hit samples a descriptor from
`IAnomalyScheduleRepository.forMatch(matchId)` weighted by
`descriptor.weight`. The selected descriptor produces an `AnomalyTriggered`
event plus an `AttributeModifier` overlaid on relevant containers via
`ResolveAnomalyEffectUseCase`. A one-shot `scheduler.runLater(duration)`
removes the modifier at expiry.

Rejected alternative: pre-compute a full firing schedule at match start.
That moves randomness off the test surface (RNG injection becomes a
single-shot rather than a per-tick override) and forces a separate "what
happens when the match runs long?" rule. Polling keeps the rule simple.

The `Random` instance is constructor-injected with `Random.Default` as the
production default; tests override with a deterministic implementation that
returns `0.0` for `nextDouble` (force-fire) and `0` for `nextInt`
(deterministic weighted pick).

### 5. Sudden Death as a uniform `AttributeModifier`

`SuddenDeathAcceleratorUseCase` subscribes to `PhaseChanged`. On
`* → SUDDEN_DEATH`, for every generator in the match it calls
`addModifier(AttributeModifier(PERIOD_MULTIPLIER, MULTIPLY, 0.5,
sourceId = "sudden-death:<matchId>"))`. On `SUDDEN_DEATH → *`, it calls
`removeModifiersBySource("sudden-death:<matchId>")`.

This is structurally identical to how Anomaly applies its modifiers
(`sourceId = "anomaly:<anomalyId>"`). HUD widgets reading
`effectiveNumber(PERIOD_MULTIPLIER)` see a single uniform truth without
caring whether the override came from Sudden Death or an Anomaly.

Rejected alternative: a phase-conditional `if (phase == SUDDEN_DEATH)
period * 0.5` branch inside the spawn loop. That makes Sudden Death special
in the use case rather than a value on the container, and HUDs would need
to know to ask the phase as well.

### 6. Placeholder events for Wave 5d/6 producers

Three domain events ship as placeholders in `match/domain/event/`:
`BlockBroken`, `EntityPickedUpItem`, `EntityEnteredRegion` /
`EntityLeftRegion`. They have no production producer in Wave 5c — the
Bukkit-side adapter that converts `BlockBreakEvent` / `EntityPickupItemEvent`
/ player-move-with-region-lookup into these domain events lands in Wave
5d/6 per `RTM-PARITY-TRACKER.md` Category 3. Listeners subscribe to the
domain events today; tests publish them directly via `FakeDomainEventBus`.

This avoids the "subscribe to nothing until Wave 5d" alternative, which
would leave the listener wiring untested until then.

### 7. Wave 5b dependency carry-over

Wave 5b (HUD ports + the read-API attribute container) had not landed when
Wave 5c started, despite the handoff doc's "完了" claim. Wave 5c:

- Inherited the partial Wave 5b attribute work (read-API +
  `ShimPlayerAttributeContainer`) on disk.
- Extended `IAttributeContainer` with the modifier surface (default
  no-op implementations preserve `ShimPlayerAttributeContainer`'s
  compatibility) per [[adr/0058]] §1.
- Added `SimpleAttributeContainer` as the modifier-aware default impl.
- Left HUD ports (`IBossBar`, `IScoreboard`, `ITabList`,
  `IMcPlayer.sendActionBar`, `IMcPlayer.sendTitle`) to a future Wave 5b
  follow-up — they are not on Wave 5c's critical path.

## Consequences

### Positive

- **Uniform pacing primitive** — Sudden Death and Anomaly use the same
  modifier shape on the same key. New pacing levers (event flags, bonus
  rounds, mutator votes) plug into the same primitive without inventing
  new state containers.
- **Mode-agnostic match domain** — `Match`, `MatchStateMachine`,
  `VictoryConditionEvaluator` stayed unchanged. Adding a mode requires only
  a new subclass + listener; the match lifecycle's invariants are not
  touched.
- **Per-instance attribute container realises [[adr/0058]] §5 commitment**
  — Wave 5c is the first impl-bearing wave; future generator/anomaly
  features live in the container natively rather than as new bespoke fields.
- **Konsist boundaries hold** — no Bukkit imports in `domain/` or
  `application/`, no raw JVM throwables in application APIs, plugin
  sibling-isolation matrix unchanged (`plugin:match` references only Core
  + Combat).

### Negative / Trade-offs

- **Per-mode listener proliferation** — six modes × event-specific
  listeners means six small classes with similar plumbing. Acceptable for
  now; a `ModeListener` base class can be extracted later if the pattern
  hardens (deferred to Wave 6).
- **Random anomaly firing surface is a balance lever** —
  `FIRE_PROBABILITY = 0.1` and per-descriptor weights need playtest tuning.
  Constants are isolated in their classes for easy adjustment.
- **Modifier-only attributes need a base-seed contract** — callers that
  add a MULTIPLY to an attribute with no stored base must set the base to
  1.0 first or the modifier silently no-ops. Documented + enforced at
  construction in `GeneratorYamlConfig.toDomain`. A long-term fix lives in
  Wave 7c (`ResolutionPolicy` per key, [[adr/0058]] §"Mitigations").
- **Placeholder events have no production producer until Wave 5d/6** —
  BreakCore / CarryTNT / CarryMiniTNT / CaptureWool / StealJewelry are
  domain-functional but won't react to a real player breaking a real block
  until the adapter ships. Acceptable for PoC closing; documented in each
  listener's kdoc.

### Mitigations

- The `astera-reviewer` agent (Phase C close review) catches any worker
  that imports Bukkit / raw `Throwable` / `kotlinx.serialization` into
  domain or application by mistake.
- The base-seed contract is exercised by
  `AdvanceGeneratorChargeUseCaseTest`'s "PERIOD_MULTIPLIER 0.5 halves the
  ticks needed to spawn" case + the production seed in
  `GeneratorYamlConfig.toDomain`.

## Alternatives Considered

- **Single strategy interface `GameMode.evaluate(match)`** — rejected (see
  §1). Forces poll-based evaluation; loses event-driven simplicity.
- **Pre-scheduled anomaly firing list** — rejected (see §4). Moves
  randomness off the test surface and complicates long-match behavior.
- **Phase-conditional `if (SUDDEN_DEATH)` branch in spawn loop** — rejected
  (see §5). Makes Sudden Death special vs. Anomaly when they should share
  a primitive.
- **Wait for Wave 5b to ship the HUD ports + finish ADR-0058 surface**
  — rejected. Wave 5c's match/generator/anomaly domain has no actual
  dependency on HUD ports; serializing waves on a paperwork claim would
  delay the PoC closing window. Wave 5c instead carries the ADR-0058
  modifier-surface forward and leaves the HUD work to a future 5b
  follow-up.

## References

- [[adr/0022]] Multi-plugin portable architecture (per-plugin internal layout)
- [[adr/0027]] Phase auto-timer (sets up SUDDEN_DEATH transitions Wave 5c
  reacts to)
- [[adr/0028]] RTM Empirical Catalog (catalog source for the 6 modes)
- [[adr/0029]] RTM Empirical Parity Tracker (rows flipped by this wave)
- [[adr/0030]] Combat depth (Wave 5a — established the
  `addModifier` / `removeModifiersBySource` ergonomics on
  `ArmorReduction` as a localized fix)
- [[adr/0058]] Universal Attribute Container — Wave 5c is the first
  impl-bearing wave; per-instance container on generators + anomalies
  realises §5 "natively from day one"
- `docs/IMPLEMENTATION-ROADMAP.md` §"Wave 5c — Game modes + Star Generator
  + Anomaly (Tier 3)"
- `docs/RTM-PARITY-TRACKER.md` Categories 2 + 5
- Wave 5c handoff doc: `/home/node/.claude/plans/wave-5c-handoff.md`
- Wave 5c plan: `/home/node/.claude/plans/wave-5c-match-mossy-kite.md`
