---
status: Accepted
date: 2026-05-30
deciders: orchestrator (Wave I 5match-wireup-fix)
supersedes: null
superseded_by: null
---

# ADR-0126: Match listener wire-up fix + Private-beta UX listeners

## Status

Accepted

## Context

The §Round 13 audit (post-merge of Round 1–12, `main` HEAD `6229f0c`) ran a
listener-subscription check across `AsteraMatchPlugin.onEnable` and surfaced
a **CRITICAL bug**: four listener classes shipped by Round 10 Wave D
5game-complete (ADR-0121 §C / §D / §E / §A) were never wired into the
event bus. The classes existed and compiled; their `eventBus.subscribe(...)`
calls did not.

The four unwired listeners:

| Listener | ADR §  | Event the listener should observe |
|---|---|---|
| `MatchCountdownListener` | §C | `MatchStarted` |
| `MatchEndSummaryListener` | §D | `MatchEnded` |
| `ApplyPvpDeathPolicyListener` | §E | `combat.PlayerKilled` |
| `AnnihilationWinConditionListener` | §A | `TeamEliminated` + `AnnihilationPhaseTransitioned` |

The most severe consequence is the Annihilation match never ending:
`AnnihilationMatchTracker.evaluateWinCondition` was read-only — no listener
called `IMatchLifecycle.end` on `WinConditionRules.Outcome.Winner`, so even
after every enemy Nexus pillar fell, the match remained in `ACTIVE` with no
exit. The audit's note: **"Annihilation will never end"**.

The same audit pass turned up three additional Private-beta UX gaps that
Round 12 deferred:

1. **`LoadoutSelectorShowIntentHandler`** — Round 10 Wave D shipped the
   handler + the `CustomizeMenuGuiSpec` tile that emits
   `loadout.selector.show`, but the router registration was never wired in
   `AsteraCorePlugin.onEnable`. Clicking the loadout tile in the lobby
   customize menu silently no-op'd.
2. **Reconnect countdown HUD** — `DisconnectDuringMatchListener` publishes
   `PlayerDisconnectedFromMatch` with `willTimeoutAtTick`, but nothing
   surfaced the held-seat to the player. Playtesters consistently misread
   the silence as a permanent kick and quit.
3. **AFK warning HUD** — `AfkSweepScheduler` publishes `PlayerAfkWarned`
   ~60 s before the auto-kick fires, but nothing rendered the warning. The
   `KickPlayerOnAfkKickedListener` recorded the moderation row only after
   the kick.
4. **Loading subtitle for cross-world transitions** — Round 8 added
   `PlayerTraveledToPveWorld` / `PlayerArrivedAtIsland` /
   `PlayerReturnedToLobby` events but the post-teleport gap had no visual
   feedback.

Phase 1 PoC posture (CLAUDE.md §Pre-release posture) prefers clean
substrates over deferring fixes; the gaps are all wireup + small new
listeners and fit a single wave.

## Decision

Wire the missing 4 listeners + register `DefaultPvpDeathPolicyResolver` in
`AsteraMatchPlugin.onEnable` (the resolver is needed by
`ApplyPvpDeathPolicyListener` but was also never registered). Add three
new HUD/UI listeners + wire `LoadoutSelectorShowIntentHandler` to close
the Private-beta UX gaps. Single wave; no new Konsist rule.

### A. Critical 4-listener wire-up + resolver registration

Add to `AsteraMatchPlugin.onEnable` after the Wave 5complete block:

- `subscriptions += eventBus.subscribe(MatchStarted::class) { matchCountdownListener.handle(it) }`
- `subscriptions += eventBus.subscribe(MatchEnded::class) { matchEndSummaryListener.handle(it) }`
- `subscriptions += eventBus.subscribe(PlayerKilled::class) { applyPvpDeathPolicyListener.handle(it) }`
- `subscriptions += annihilationWinConditionListener.subscribe()`  — the listener owns its dual subscribe.
- `DefaultPvpDeathPolicyResolver` registered at priority 0 via the existing `IPvpDeathPolicyResolver` port.

All four subscriptions go into the existing `subscriptions` list so the
Wave 5server-harden (ADR-0123 §4) cancel-on-disable pass cleans them.

### B. `LoadoutSelectorShowIntentHandler` wireup

Wire in `AsteraCorePlugin.onEnable` next to the Wave G handler block (same
shared `IGuiIntentRouter`, same `waveGRegistrations` cleanup list):

```kotlin
waveGRegistrations += waveGRouter.register(
    LoadoutSelectorShowIntentHandler.INTENT_PREFIX, // "loadout.selector."
) { intent, player -> loadoutSelectorShowHandler.handle(intent, player) }
```

The handler joins `waveGIntentRegistrations` so `onDisable` releases the
captured lambda along with the Wave G handlers.

### C. `ReconnectCountdownHudListener` (new)

`plugin/match/.../application/eventbus/listeners/hud/ReconnectCountdownHudListener.kt`.

Subscribes to `PlayerDisconnectedFromMatch` + `PlayerReconnectedToMatch`.
On a non-null `willTimeoutAtTick`, starts a 20-tick (1 s) repeating
`IScheduler` task that pushes an `HudLayout.ActionBar` with one of three
coarse i18n keys (`astera.match.reconnect.countdown.{long,medium,short}`)
based on `(deadline - now) / 20 / 60` minutes left.

When the per-tick callback observes `currentTick >= willTimeoutAtTick`,
it pushes one final `astera.match.reconnect.expired` frame and self-cancels.
`PlayerReconnectedToMatch` cancels the running task. Per-player
`IScheduler.TaskHandle`s tracked in a `ConcurrentHashMap` and drained by
the existing `AsteraMatchPlugin.onDisable` subscriptions sweep.

**Phase 1 PoC**: three coarse messages (>=2 min / >=1 min / <1 min) instead
of `{minutes}:{seconds}` placeholders, because `HudComponent.Text` is
i18n-key-only — Wave 6b's `HudComponent.TextWithPlaceholders` (deferred)
will thread the second-precision value. Minute-grain is acceptable
playtest feedback.

### D. `AfkWarningHudListener` (new)

`plugin/core/.../application/eventbus/listeners/afk/AfkWarningHudListener.kt`.

Subscribes to `PlayerAfkWarned` (warning push) + `PlayerAfkKicked` (explicit
clear). Pushes `astera.match.afk.warning` to the ActionBar on warn; clears
the ActionBar region on kick. Recovery to Active doesn't publish an event
by design — the ActionBar fades naturally via its `ttlTicks` once the
sweeper stops re-publishing.

### E. `LoadingProgressListener` (new)

`plugin/core/.../application/eventbus/listeners/loading/LoadingProgressListener.kt`.

Subscribes to the Round 8 travel events:

- `PlayerTraveledToPveWorld` → `astera.match.loading.pve-world`
- `PlayerArrivedAtIsland` → `astera.match.loading.island`
- `PlayerReturnedToLobby` → `astera.match.loading.lobby`

Pushes one `HudLayout.Subtitle` per arrival. Actual 0 % → 100 % progress
tracking is deferred to Wave 6b's PaperWorldGateway integration; this
listener is the placeholder visual that closes the "did the teleport
land?" feedback gap.

### F. i18n parity

Added 9 new keys to `content/languages/en.yaml` + `ja.yaml`:

- `astera.match.reconnect.countdown.{long,medium,short}` (3 × 2)
- `astera.match.reconnect.expired` (1 × 2)
- `astera.match.afk.warning` (1 × 2)
- `astera.match.loading.{pve-world,island,lobby}` (3 × 2)

`en.yaml` ↔ `ja.yaml` key-parity Konsist rule continues to hold.

### G. Konsist unchanged

No new architectural rule. Wave I is pure wireup + 3 small listeners that
follow the existing listener patterns; nothing new requires invariant
enforcement. Konsist rule count stays at 53.

### H. Regression test

New `AnnihilationWinConditionEndToEndScenarioTest` in `plugin/match/src/test`
exercises the full chain:

1. Start an Annihilation match (red / blue, 1 player each)
2. Seed `AnnihilationMatchTracker` with both teams
3. Advance through Phase 1 → Phase 2 → Phase 3 (opens Tier 3 for combat)
4. `recordDamage(redTier3, pillarHp)` → tracker publishes `TeamEliminated`
5. Listener observes → calls `IMatchLifecycle.end(ObjectiveCompleted)`
6. `EndMatchUseCase` publishes `MatchEnded`

If step 5's wire-up regresses, the `MatchEnded.hasSize(1)` assertion fails
with a guidance message pointing back to this ADR. A second test asserts
`AnnihilationWinConditionListener.subscribe()` returns both event
subscriptions so the cancel-on-disable cleanup covers both paths.

## Consequences

### Positive

- **Annihilation matches actually end.** The single most impactful regression
  fix in §Round 13. Without this, Annihilation is unshippable.
- **Match countdown, summary, and PvP death policy listeners do their
  jobs.** Round 10's ADR-0121 §C/§D/§E payloads were dead code before
  this; they now actually fire.
- **Private-beta playtesters get feedback** on the three previously-silent
  UX paths (reconnect, AFK, loading). Anecdotal pre-fix feedback ("did I
  get kicked permanently?" / "did the teleport go through?") is closed by
  the visible cues.
- **`DefaultPvpDeathPolicyResolver` is finally registered** — without it,
  any future consumer that resolves `IPvpDeathPolicyResolver` would get
  null. The audit gap is closed.
- **Cleanup discipline preserved.** All new subscriptions go into the
  existing cancel-on-disable lists (`subscriptions` in match,
  `wave5completeSubs` / `waveGRegistrations` in core), so the
  Wave 5server-harden (ADR-0123 §4) listener-cleanup audit continues to
  pass.

### Negative / Trade-offs

- **Reconnect countdown is minute-grain, not second-grain.** Three coarse
  messages instead of a live `2:45` ticker. Acceptable for Phase 1 PoC;
  the proper fix is `HudComponent.TextWithPlaceholders` in Wave 6b.
- **Loading subtitle is a placeholder, not real % progress.** Until
  PaperWorldGateway lands async-load polling (Wave 6b), it's just "show
  this for ~2 seconds". Better than nothing; not as good as a progress
  bar.
- **AFK warning ActionBar may briefly linger after a recover-to-Active.**
  No "recovered" event fires from `AfkSweepScheduler` by design (sweeper
  state diff: Active is the absence of a transition). The fade timer
  resolves the ghost within ~2 s. Not worth changing the sweeper contract
  for.
- **Match summary text is chat + subtitle, not a rich GUI screen.**
  Deferred to Wave 6b / Phase 4 per ADR-0121 §D's original scope. This
  ADR doesn't change that.

### Mitigations

- The Annihilation E2E scenario test guards against the regression
  recurring — any future Plugin.kt rewrite that drops a subscription
  fails the test with a clear message.
- The three new HUD listeners write debug-level logs on per-player
  failures; operators can `tail -f` to confirm pushes are reaching the
  gateway during private beta.
- The MessageKey-only ActionBar limitation is acknowledged inline in
  `ReconnectCountdownHudListener` with a pointer to the Wave 6b fix.

## Alternatives Considered

- **Add a new `PlayerSessionStateChanged` event** instead of using the
  existing `PlayerDisconnectedFromMatch` / `PlayerReconnectedToMatch`
  pair. Rejected: the existing events already carry every field the HUD
  listener needs (`willTimeoutAtTick`, `playerId`, `matchId`). Inventing
  a third event would add a parallel publish path inside
  `DisconnectDuringMatchListener` for zero gain.
- **Place `AfkWarningHudListener` in `plugin/match` next to the match-side
  HUD listeners.** Rejected: AFK is a Core domain concern (the events
  live in `core.domain.event`, the sweeper lives in `core.application`).
  Match-isolation Konsist rule would also forbid match → core afk import.
- **Use `HudLayout.Subtitle` for the AFK warning.** Rejected: subtitle is
  a one-time "look here" cue. The AFK warning needs to persist (or
  re-render) so the player notices on return from the keyboard.
  ActionBar's natural fade + re-push pattern fits.
- **Add a Konsist rule "every listener class created must be wired in
  some Plugin.kt".** Rejected (for this wave): the rule is broad — many
  listeners are conditionally wired (Combat softdep) and the false-
  positive surface would dwarf the value. A targeted "subscriptions:
  MutableList holder pattern" rule already exists from
  Wave 5server-harden (ADR-0123 §4) and partially addresses the gap.
  Revisit if Round 14+ ships more deferred listeners.

## References

- 関連 ADR: [[adr/0121-game-system-completion]] (the 4 listeners' origin)
- 関連 ADR: [[adr/0108-annihilation-game-mode]] (Annihilation tracker / win condition)
- 関連 ADR: [[adr/0123-server-hardening-and-safety]] (§4 listener cleanup pattern)
- 関連 ADR: [[adr/0079-extract-studio-to-own-repo]] (§6 reconnect window 3-min)
- 関連 ADR: [[adr/0083-afk-detection]] (AFK events)
- 関連 ADR: [[adr/0097-hud-gateway]] (IHudGateway / HudLayout)
- 関連 ADR: [[adr/0116-wave-5wireup]] (G13/G14/G15 travel events)
- 関連 docs: [[architecture/principles]] (Clean Architecture / IDomainEventBus)
- 関連 docs: [[RTM-PARITY-TRACKER]] (Wave I 5match-wireup-fix row)
