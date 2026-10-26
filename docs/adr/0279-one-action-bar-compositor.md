---
status: Accepted
date: 2026-10-27
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0279: One action-bar compositor; direct action-bar sends are forbidden

## Status

Accepted. Builds on [ADR-0182](0182-resource-pack-pipeline-and-hp-status-hud.md) (the HP status line is drawn on the action bar)
and ADR-0177 (one handle per HUD region, action-bar priority).

## Context

The HP / status line is drawn on the action bar. The client has exactly one action bar, so any `Player.sendActionBar`
replaces the HUD until the next refresh (up to a second). Founder report: picking up a star made the HUD vanish for a
moment. The cause was `PaperPresentationVisuals.actionBar` (star pickup, star burst and every other presentation
moment with `action_bar:`) calling Adventure directly. The same hole existed in `PaperPlayerFeedbackGateway`
(the `SendActionBar` skill mechanic), `PaperLobbyMenuKit` (key hint) and the fallback path of `PaperPlayer`.
`PaperHudGateway` already composed readout and status line correctly for callers that used `IHudGateway`.

## Decision

1. `IActionBarChannel` (`kernel:runtime`) is the single entry point for transient action-bar messages:
   `post(player, component, priority, ttlTicks)` plus `post(key, placeholders)` / `postRendered(minimessage)`
   helpers. Default priority is 1 (above the priority-0 cooldown readout), default TTL 40 ticks.
2. `HudActionBarChannel` implements it as an `ActionBar` layout on `IHudGateway`. `PaperHudGateway` is the
   compositor: it merges the message with the HP status line in one component (message first, then the HP block
   placed by `StatusLineAnchor` over the heart row), so HP is never hidden, and re-sends at the status refresh cadence
   (20 ticks) and on expiry. A live higher-priority message is not overwritten by a lower one.
3. `PaperHudGateway.kt` is the only file allowed to call the platform action-bar send. Konsist rule
   `action bar is sent only by the HUD compositor` fails the build on any other `sendActionBar(` call or
   action-bar packet in main sources.
4. `IMcPlayer.sendActionBar` and `IPlayerFeedbackGateway.sendActionBar` are renamed `postActionBar`; they route to the
   channel and never fall back to a direct send (a missing channel drops the message).
5. New `HudComponent.Rendered(miniMessage)` carries already-rendered text (skill inline text) through the channel.

## Consequences

### Positive
- The HUD is continuously visible; the star pickup line is merged with it.
- Adding a new action-bar use cannot reintroduce the bug: the build fails.

### Negative / Trade-offs
- A toast is shown for its TTL; a lower-priority message posted during a higher one is dropped, not queued.

### Mitigations
- Priorities: 0 persistent readout, 1 default toast; raise it for messages that must win.

## Alternatives Considered

- **Reserved segment, restore HUD afterwards**: leaves a gap frame where the HP line is missing. Merging avoids it.
- **Lint only, no port**: does not give callers a priority and TTL model.

## References

- [[adr/0182-resource-pack-pipeline-and-hp-status-hud]]
