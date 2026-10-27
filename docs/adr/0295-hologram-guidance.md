# ADR-0295: Per-player hologram guidance

- Status: Accepted
- Date: 2026-10-28
- Items: MODE-16 (in-match navigation), UI-11 (lobby tutorial reuses it)

## Context

Founder 2026-10-28: players do not know where to go in a match; text alone is not enough, a visual "hologram assist" was asked for. Carry-tnt and the other objective modes already show an action-bar compass line, which is easy to miss while fighting. The existing `IHologramGateway` shows fixed holograms with i18n lines and cannot show a moving, per-player arrow with a live distance.

## Decision

A vendor-neutral port `IHologramGuide` (`kernel/runtime`, `core.application.port.outbound`): `point(viewer, target, label)` and `hide(viewer)`. One guide per viewer: an arrow that floats about 2 blocks ahead of the viewer at eye height, turns toward the target as the viewer looks around and reads `<label> 23m`. The Paper implementation `PaperHologramGuide` (core, `adapter.paper.hologram`) is one `TextDisplay` per viewer, created invisible-by-default and revealed with `Player.showEntity` to that viewer only; a 2-tick task follows the viewer and redraws arrow and distance.

Lifetime: the callers refresh the target about once a second and every call carries a time-to-live (50 ticks), so a forgotten guide removes itself. The entity is also removed when the viewer quits, dies or changes world, when the match ends (`ObjectiveKit.endMatch`) and on plugin shutdown. Entities are tagged as Astera-managed so the janitor sweeps strays.

The match objective kit (`ObjectiveKit.compass`, used by carry-tnt, carry-mini-tnt, steal-jewelry) feeds the guide from the same target as the action-bar compass, with the short label `match.nav.<mode>.<hint>`. No TNT: the loose or dropped TNT. Carrying: the nearest enemy base. A teammate carries: that carrier. An enemy carries: that carrier (the defenders' threat). Players who turned the HUD off (`PlayerSettings.hpLineVisible`) get no hologram. The lobby tutorial (UI-11) can call the same port.

## Consequences

### Positive
- The next objective is visible in the world, not only in the action bar; per-viewer, no visibility leak to others.
- One port serves match and tutorial.

### Negative / Trade-offs
- One display entity and a 2-tick task per guided player; the arrow is a text glyph in eight steps, not a rotated model.
- HUD gating reuses the HP-line toggle until a dedicated setting exists.

## Alternatives Considered

- **Extend `IHologramGateway`**: its text is i18n keys without live parameters and its scope is per-hologram, not follow-the-viewer. Rejected.
- **Route line of particles / A\***: more readable but costly; direction and distance first, path later.

## References

- `kernel/runtime/.../port/outbound/IHologramGuide.kt`, `core/feature-gameplay/.../hologram/PaperHologramGuide.kt`
- `plugin/match/.../gamemode/siege/ObjectiveKit.kt`
- `tools/game-sim/.../HologramGuideScenarioTest.kt`
