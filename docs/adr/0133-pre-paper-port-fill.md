---
status: Accepted
date: 2026-05-31
deciders: Astera core
supersedes: null
superseded_by: null
---

# ADR-0133: Pre-Paper port fill — IDamageGateway InMemory default + PostgresBlockDurabilityRepository registration

## Status

Accepted

## Context

Round 16 audit γ verification (3 Explore agents in parallel) re-checked 5
"suspicious orphan port" candidates flagged by Round 15 audit γ before
Wave 6 Paper sweep. 3 of the 5 (IComboTracker / IFocusFireTracker /
ITargetLockController) were already verified properly wired in Round 15.
The remaining 2 surfaced as genuine pre-Paper-sweep gaps:

### Gap §A — `PostgresBlockDurabilityRepository` registration missing

- Class exists at
  `plugin/persistence/src/main/kotlin/.../repository/PostgresBlockDurabilityRepository.kt`
- The class KDoc explicitly states "Registered by `AsteraPersistencePlugin`
  at priority=100; overlays the in-memory default
  `InMemoryBlockDurabilityRepository` (priority 0) installed by
  `AsteraCorePlugin`."
- Reality: `AsteraPersistencePlugin.onEnable` imports the class but
  **never calls `services.register<IBlockDurabilityRepository>(...)`**
- Result: block-durability state in production runs as in-memory-only
  even with the Postgres plugin loaded — pod restart loses every block
  durability tick. V20 migration (`block_durability` table) is applied
  but never read or written. Wave 5x (ADR-0090) intent silently dropped.

Same family of bug as Wave I 5match-wireup-fix (Annihilation listener
constructed but never `subscribe()`-d): the impl class is shipped, the
contract is documented, but the wiring line was forgotten.

### Gap §B — `IDamageGateway` has no impl + null-gated callers

- Port: `plugin/combat/src/main/kotlin/.../application/port/outbound/IDamageGateway.kt`
- Only impls today: `FakeDamageGateway` (testfixtures) and Wave 6a's
  `PaperDamageGateway` (TBD).
- `AsteraCombatPlugin.onEnable:273` does `services.get<IDamageGateway>()` —
  returns null. The downstream code at line 297 null-gates the entire
  `ApplyDamageUseCase` construction (`if (damageGateway != null && …)`).
- Result: the **entire damage pipeline silently does not wire** in Phase
  1 dev / PoC sessions. The combo / focus-fire / target-lock damage-bonus
  chain (Wave K 5integration-wire / ADR-0128) — which Round 15 explicitly
  verified is "intact + properly wired" — runs through `ApplyDamageUseCase`
  that itself never exists, so the bonus chain is dead-end code today.

This is a hidden silent-drop pattern: Round 15 audit γ initially treated
this as a Wave-6-Paper-deferred gap, but the lack of any in-memory default
means the bonus chain shipping in Wave K cannot be exercised end-to-end
until Wave 6a Paper adapter ships. Phase 1 PoC closing therefore has zero
real validation that Wave K's chain actually flows through `ApplyDamage`.

The 4 existing ADR-0072 Wave 6 deferred ports (`IMatchTimerService` /
`IPlayerMovementGateway` / `IPlayerVisionGateway` / `IStructurePaste`)
all share the same shape (no InMemory default, callers null-gate), but
none of them sit on a critical multi-wave integration chain. IDamageGateway
is uniquely load-bearing because Wave K's bonus chain is built ON TOP OF
ApplyDamageUseCase.

## Decision

Two narrow fixes shipped as a single wave:

### Decision §A — Add `PostgresBlockDurabilityRepository` registration

In `AsteraPersistencePlugin.onEnable`, append:

```kotlin
registrations += services.register<IBlockDurabilityRepository>(
    PostgresBlockDurabilityRepository(ctx.database),
    priority = 100,
)
```

Right after the existing Wave 5pve-survival block (priority 100 grouping).
Also append `IBlockDurabilityRepository` to the closing `logger.info(...)`
registration roster.

No new test (existing IT in `:plugin:persistence` already covers
`PostgresBlockDurabilityRepository`; the bug was wiring, not behavior).

### Decision §B — Ship `InMemoryDamageGateway` stub (priority 0)

New class
`plugin/combat/src/main/kotlin/.../application/service/InMemoryDamageGateway.kt`:

- Implements `IDamageGateway.apply(target, resolved, source)` returning
  `Result.failure(DamageApplicationError.TargetOffline(target))` for
  every call.
- Logs at DEBUG so operators see no-op patterns in dev logs without
  flooding INFO during PvE auto-attack loops.
- Registered at priority 0 in `AsteraCombatPlugin.onEnable` right before
  the existing `services.get<IDamageGateway>()` lookup (line ~273).
- Wave 6a `PaperDamageGateway` will overlay at priority 100 (per existing
  Astera priority-overlay convention).

This UNWIRES the existing null-gate at AsteraCombatPlugin:297, letting
`ApplyDamageUseCase` actually construct + register. The combo / focus-fire
/ target-lock damage-bonus chain (Wave K / ADR-0128) now runs end-to-end
through `ApplyDamageUseCase` in Phase 1 dev sessions; every damage attempt
returns `TargetOffline`, which is the correct semantic for a Phase-1-PoC
with no game-world hit box to write hp to.

Unit test verifies `apply` returns `Failure(TargetOffline)` for a
representative call (1 test). Coverage is intentionally minimal — this is
a Phase 1 PoC stub.

## Consequences

### Positive

- **Closes a hidden silent-drop**: Wave K's bonus chain can now be
  exercised end-to-end in Phase 1 dev sessions (was effectively dead
  code waiting for Wave 6a Paper adapter).
- **Closes a true wiring bug**: block-durability persistence finally
  works as documented (Wave 5x / ADR-0090 / V20 migration intent).
- **Stays consistent with priority-overlay convention**: in-memory at 0,
  Paper at 100 (Wave 6a will trim with zero conflict).
- **Zero test churn**: 1 minimal test for the stub, no changes to
  existing damage pipeline tests (which use FakeDamageGateway directly).

### Negative / Trade-offs

- **`InMemoryDamageGateway` is mildly deceptive at first glance**: it
  appears wired but always no-ops. Mitigated by class name
  (`InMemory*Gateway` = Astera convention for "test/dev impl, expect
  Paper overlay") + DEBUG log line + extensive KDoc.
- **Damage pipeline is now wired but functionally no-op until Wave 6a**:
  operators may briefly wonder why damage doesn't apply. The DEBUG log
  line "InMemoryDamageGateway (Phase 1 PoC stub): no-op for target=…"
  makes the cause discoverable.

### Mitigations

- KDoc on `InMemoryDamageGateway` explicitly says "no live Paper damage
  adapter (Wave 6a will land `PaperDamageGateway` over Bukkit
  `Damageable.damage`)" and explains why a stub is preferable to leaving
  the port unregistered.
- The TargetOffline error variant is the most semantically accurate
  failure mode for "this gateway has no game world to write to" — not a
  generic NotImplemented / placeholder.

## Alternatives Considered

- **Alt A: Add `IDamageGateway` to ADR-0072 Wave-6-deferred allow-list
  instead of shipping an InMemory stub**: more honest about the gap, but
  leaves Wave K's bonus chain dead in Phase 1 dev sessions. Rejected:
  user explicitly asked for "Paper の前にやるべきことを全て" (everything
  before Paper), and shipping the stub is concrete pre-Paper work that
  unlocks Wave K validation, whereas allow-list entry is purely
  documentation of a deferral.
- **Alt B: Add `IDamageGateway` to ADR-0072 allow-list AND ship the stub**:
  double-counting. ADR-0072 allow-list is for "intentionally Wave-6-
  Paper-deferred with no in-memory fallback". With a stub shipped, this
  port no longer belongs in that allow-list. Rejected.
- **Alt C: Leave Persistence registration missing, rely on in-memory
  fallback**: would leave the V20 migration permanently unused and Wave
  5x intent silently dropped. Rejected — this is a wiring bug worth
  fixing now.
- **Alt D: Make `InMemoryDamageGateway` return `Success(HpReduced(0.0))`
  instead of `Failure(TargetOffline)`**: would silently claim damage was
  applied when nothing happened. Worse than the current stub because
  downstream code (e.g. `PlayerKilled` publisher) would compute against
  a phantom HP value. Rejected — fail-fast semantics are correct.

## References

- 関連 ADR:
  [[adr/0022-multi-plugin-layout]],
  [[adr/0030-damage-pipeline-and-result-error]],
  [[adr/0072-pre-paper-port-commitments]],
  [[adr/0090-block-state-and-durability]] (Wave 5x),
  [[adr/0128-cross-feature-integration-wire]] (Wave K damage-bonus chain),
  [[adr/0131-konsist-event-listener-pairing-rules]] (Wave I silent-drop
  pattern reference)
- Audit γ Round 15 (initial) + Round 16 (verification) — pre-Paper-sweep
  port readiness audit
- Future work: Wave 6a will ship `PaperDamageGateway` (priority 100) and
  the `InMemoryDamageGateway` stub will continue to exist as the
  test/dev fallback (consistent with `InMemoryPlayerHealthGateway` /
  `InMemoryPlayerSpeedGateway` / etc.)
