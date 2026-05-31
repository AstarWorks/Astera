---
status: Accepted
date: 2026-05-31
deciders: Astera core
supersedes: null
superseded_by: null
---

# ADR-0134: Konsist DomainEvent publisher-exists rule (Wave K prevention)

## Status

Accepted

## Context

Round 15 ADR-0131 §Alternatives §Alt A explicitly deferred the
"DomainEvent publisher↔subscriber pairing rule" — the full version
required a ~149-entry allowlist baseline (events whose ONLY consumer is
the wildcard `subscribe(DomainEvent::class)` analytics handler), and
maintaining such a baseline was deemed too brittle for Phase 1 PoC
closing.

Round 16 Wave P revisits this work with a scope-shrunk variant that lands
without the 149-entry baseline.

### Scope-shrink rationale (Option ii from Round 16 audit β re-design)

Astera ships exactly one wildcard subscriber: `InMemoryScheduledAnnouncementSystem`
(Core / application/service/admin) holds an
`eventBus.subscribe(DomainEvent::class)` that polymorphically catches every
DomainEvent subtype (verified by `InMemoryDomainEventBus.publish`:
`entry.type.isInstance(event)` invokes the handler for any subtype, not
just exact-type matches). With this wildcard subscriber wired:

- **Subscriber side**: every DomainEvent subtype is vacuously consumed
  (at least by the analytics handler). A per-type subscriber check is
  vacuously true and provides zero CI value today.
- **Publisher side**: still meaningful — an event with NO publisher is a
  dead-letter pipe waiting for someone to remember to add the publisher.
  The subscriber sits forever; downstream features (HUD updates, audit
  records, analytics) silently never fire.

Therefore Rule 55 checks **publisher-exists only**. If the wildcard
subscriber is ever removed (or restricted), a future ADR can re-introduce
the subscriber-side check. The publisher check captures the load-bearing
half of the original Wave K silent-drop prevention intent.

### Motivating incident (same as ADR-0131)

Wave K 5integration-wire (ADR-0128) shipped 7 events whose **subscribers**
were missing — the symmetric case to this rule. ADR-0131 Rule 54 catches
the listener-var leak side (`val xxxListener = ...` never referenced);
this ADR's Rule 55 catches the event side (`class XxxEvent : DomainEvent`
declared, but no caller ever constructs it). Both halves of the silent-drop
pattern are now CI-enforced.

Concretely: a Phase 2 contributor who writes `data class XxxEvent : DomainEvent`
but forgets to publish from any use case would have shipped today and the
event sat as dead code until somebody noticed. Rule 55 now refuses such a
commit.

### Allowlist baseline (29 entries)

Running Rule 55 today surfaces exactly **29 events** that lack a publisher.
Categorized:

- **15 entries — Wave 6a Bukkit-event-adapter deferred**: `PlayerJoinedServer`,
  `PlayerQuitServer`, `PlayerInteractedBlock`, `PlayerTeleported`,
  `ChunkLoaded`, `ChunkUnloaded`, `WorldUnloading`, `WeatherChanged`,
  `TimeChanged`, `MobKilled`, `EntityEnteredRegion`, `EntityLeftRegion`,
  `EntityPickedUpItem`, `EnvironmentDamageApplied`. Wave 6a's
  `BukkitEventAdapter` (or sibling Paper-side adapter) will publish each
  of these by translating the corresponding Bukkit event.
- **2 entries — Wave 6b Velocity-proxy-adapter deferred**:
  `PlayerArrivedOnServer`, `ProxyHeartbeat`. Wave 6b's
  `VelocityProxyMessenger` will publish (subscriber-side stubs already
  wired by Wave O 5cross-server-wire / ADR-0132).
- **12 entries — Phase 2+ feature waves**: 3 Mining events, 2 NPC events,
  3 Tutorial-lifecycle events (beyond Wave 5q's `TutorialStepEntered`),
  2 Portal events, 2 PvE reward events, 1 Role expiry event. All
  scaffolded for future feature waves that are not yet on the
  IMPLEMENTATION-ROADMAP.

Total: 29 entries, vs 149 if a subscriber-side check were also enforced —
a 5× reduction in baseline burden that still preserves the Wave-K-style
silent-drop catch.

## Decision

`tools/architecture-test/src/test/kotlin/com/astarworks/astera/architecture/ArchitectureTest.kt`
gains Konsist Rule 55 (54 → 55 total):

### Rule 55: Every DomainEvent must have a publisher

For each concrete class `XxxEvent` implementing `DomainEvent` (located in
`plugin/*/src/main/kotlin/**` excluding `tools/architecture-test/` and
`test/`), verify a constructor call `XxxEvent(` exists in at least one
main-source file **outside** the event's defining file.

- Sealed and abstract DomainEvent classes are excluded (interface markers,
  not concrete events).
- Allowlist: `tools/architecture-test/src/test/resources/konsist-allowlist/deferred-events.txt`
  (1 FQN per line, `#` starts a comment, blank lines ignored). Each
  section header documents the deferral category (Wave 6a Bukkit adapter,
  Wave 6b Velocity adapter, Phase 2+ feature) and each entry SHOULD
  have an inline comment naming the source Bukkit/Velocity event or
  feature wave that will eventually publish it.
- Subscriber side intentionally NOT checked (see §Context — wildcard
  analytics consumer makes per-type subscriber check vacuous; ~149-event
  baseline too brittle for Phase 1 PoC closing).

The rule fails with a sorted list of `XxxEvent` FQNs that lack any
constructor call outside their own file. The failure message points at
the allowlist path and at ADR-0134 for the deferral procedure.

### Allowlist content (29 entries, populated at land time)

See `tools/architecture-test/src/test/resources/konsist-allowlist/deferred-events.txt`
for the categorized full list. Annual hygiene: review every entry once
per release cycle; a line removed from the file is a **positive event**
(it means the publisher shipped).

## Consequences

### Positive

- Closes the Wave K-style silent-drop pattern from the event side:
  declaring a `data class XxxEvent : DomainEvent` and forgetting to
  publish it is now a CI failure.
- Complements ADR-0131 Rule 54 (listener-side silent-drop catch). Together,
  the two rules cover both halves of the "declared but not wired"
  anti-pattern.
- 29-entry allowlist baseline is a 5× reduction vs the full
  publisher↔subscriber pairing variant (149 entries). Categorized
  inline comments make each deferral auditable.
- Each Wave 6a / 6b / Phase 2 wave will trim its allowlist entries as the
  publisher ships — providing a forward-looking "remaining work" ledger
  that survives release planning.

### Negative / Trade-offs

- **Subscriber side not checked**: an event whose publisher exists but
  whose subscriber is missing slips through Rule 55. Wave K shipped this
  exact bug; ADR-0131 Rule 54 catches it from the listener-var side
  (constructed listener never referenced), but a Wave K-style "I forgot
  to add the subscribe call" bug where the listener class itself is
  absent would not be caught by either rule. This is a known limitation;
  re-introducing the subscriber check requires removing the wildcard
  analytics consumer first (out of scope).
- **Allowlist file is large (29 entries)**: more boilerplate to maintain
  than a zero-allowlist rule. Mitigated by per-section comment headers
  and per-entry inline Bukkit/Velocity-event mapping.
- **Text-scan based**: same trade-off as ADR-0131 Rule 54. Konsist's
  KoFile API + regex; AST-level constructor-call tracking is not
  performed. False positives possible if a constructor call appears in
  a string literal or comment; in practice the `\bXxxEvent\(` regex is
  precise enough for the Astera convention.

### Mitigations

- Allowlist file has detailed section headers + per-entry inline comments
  pointing at the specific source event / feature wave that will provide
  the publisher.
- Rule failure message names the allowlist file path explicitly and
  back-references ADR-0134 for the deferral procedure.
- Annual hygiene cadence (per release cycle review) keeps the allowlist
  from rotting; ADR-0131 introduced the same cadence for its allowlist
  and the procedure carries over.

## Alternatives Considered

- **Alt A: Full publisher↔subscriber pairing rule with 149-entry
  baseline**: rejected per ADR-0131 §Alternatives §Alt A — too brittle
  for Phase 1 PoC closing.
- **Alt B: Pattern-based exemption (e.g. exempt events ending in `Joined`
  / `Loaded` / `Unloaded`)**: regex pattern exemption is opaque and
  brittle. Explicit FQN allowlist is more auditable.
- **Alt C: Defer publisher check too (no Rule 55, wait for Wave 6+ to
  shrink the natural violator count)**: leaves the Wave K-style
  forget-to-publish bug open for the entire Phase 1 PoC closing window.
  The 29-entry allowlist is cheap; the bug class it prevents is
  expensive.
- **Alt D: Separate rule for each deferral category (Wave 6a / Wave 6b /
  Phase 2+)**: triple the rule count for no real gain. One rule with
  one categorized allowlist file is more maintainable.

## References

- 関連 ADR:
  [[adr/0022-multi-plugin-layout]],
  [[adr/0128-cross-feature-integration-wire]] (Wave K motivating incident),
  [[adr/0131-konsist-event-listener-pairing-rules]] (Rule 54 — listener-
  side silent-drop catch; §Alternatives §Alt A → this ADR is the
  scope-shrunk variant of the originally-deferred publisher↔subscriber
  rule),
  [[adr/0132-cross-server-event-subscribers-stub]] (Wave O — subscriber
  side wired for ProxyHeartbeat / PlayerArrivedOnServer; publishers still
  pending Wave 6b Velocity adapter)
- 関連 docs: `.claude/rules/coding-posture.md` (Cross-plugin coordination),
  `tools/architecture-test/.../ArchitectureTest.kt` (Konsist SSOT)
- Allowlist: `tools/architecture-test/src/test/resources/konsist-allowlist/deferred-events.txt`
- Future work: when the wildcard analytics consumer is restricted or
  removed, a follow-up ADR can re-introduce the subscriber-side check
  (originally Rule 1 §Alt A in ADR-0131).
