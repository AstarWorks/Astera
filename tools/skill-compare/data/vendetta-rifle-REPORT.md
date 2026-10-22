# Vendetta Bayonet — source-grounded revision

Branch: `codex-vendetta-rifle`. Content only; no Kotlin edits and no test suites.
Source: `wbu2viInM3w` (24 fps native), supplied wiki kit plus inspected 24 fps sheets and short 30 fps resamples. Only Meursault choreography is used; the first hall sequences for Sinclair are excluded. Earlier analyses confused the held ribbed blade with a floating rifle battery. That battery and eight-hit ranged barrage have been removed.

| Binding | Source interval | Coins / authored damage events | Range | Real-client comparison |
|---|---|---|---|---|
| left_click / S1 | 47.50–50.20 | 2 / 2 | dash-melee | pending |
| right_click / S2 | 50.55–54.80 | 3 / 3 | dash-melee | pending |
| sneak_right / S3 | 55.80–64.20 | 3 / 3 | dash-melee | pending |
| sneak_left / DEF | 94.00–96.80 | 2 / 2 | melee counter approximation | pending |
| drop / Q | 104.40–117.50 | 5 / 5 | dash-melee enhanced flurry adaptation | pending |

Q costs 100 E.G.O and zero ammo. **The source depicts enhanced S3, not a separate E.G.O**; Q is the requested slot adaptation. Defense's two outgoing hits are based on the wiki: the reference passage shows clashes, not a clean resolved counter. Do not identify the enemy's yellow sweep as the caster's attack.

## Implementation

Five original skill names/bindings, 12-round pool, S1/S2/S3 ammo costs 1/2/3; reload remains Shift+Q. Each coin is a separate damage mechanic with invulnerability bypass. Charge eruptions are cosmetic. Rifle curved spine, grip and seven ribs are block-display rig parts. Swept fans combine opaque orange, translucent red, a gold edge and dark rim; no glow outlines. S3/Q have five-lobe upward bursts, particles and brief caster-only impact overlays. Full source pacing is retained rather than shortening the video for gameplay.

Analytical display bound after ACT1: each 200° swing with out-quad easing traverses 87.5°, 62.5°, 37.5°, 12.5° in its four intervals. Sampling at 30° gives ceil(87.5/30)+ceil(62.5/30)+ceil(37.5/30)+ceil(12.5/30) = 3+3+2+1 = 9 samples. Two solid bands + edge = 27 trail displays; 11 rig parts gives 38. The second S2 swing starts 8 ticks after the first; with six-tick trails and the two-tick freeze, at most the final first-swing sample survives into the second's full fan. Bound = 38 + 11 old rig + 3 old trail + 3 punctuation = 55. Ready overlap = 38+11 = 49; heavy burst overlap = 38+18+3 = 59. All are ≤60. Particles consume no display slots. Final counts are analytical, not runtime telemetry.

## Limits to verify in pairs

- Vanilla body animation cannot reproduce the hand-drawn crouch, coat silhouette, facial inserts or squash/stretch. Rig motion approximates the held blade independently of player limbs.
- Circular swept fans approximate irregular elliptical brush strokes; size estimates carry ~20% uncertainty where actors are crouched/occluded. Per-case notes include formulas and explicit unmeasurable features.
- Heavy source choreography reverses sides and includes a leap. Current content closes once, adds a vertical leap before the finisher, and holds a forward-facing melee position. This is an implementation approximation, not a claimed engine impossibility.
- The sequence schema supplies timed effects, not a clashable counter event. Defense currently grants evasive and plays two timed close-range replies; the AI-free capture dummy cannot supply the source's incoming attacks.
- Unique ammunition tiers, enhanced-form unlocks, Tremor–Scorch conversion and turn-based Burn/Tremor counts do not have direct equivalents in the current bindings; ordinary ammo/Tremor and cosmetic ignition approximate them.
- Spectator comparison cameras can cut, but gameplay's screen overlays target only the caster. A spectator cannot validate caster-only overlay coverage.

## PDCA evidence

S1 iteration 1 captured and inspected: `build/pairs/vendetta-rifle-s1-iteration-1.png`. Failed silhouette/scale/camera checks; ACT1 narrows and subdivides the fan, adds solid two-sided bands, exposes the muzzle core, brings the rig toward camera, follows the ready rig during dash, and tightens the face insert. Remaining captures/recapture are queued. No match claimed.

Validation: `./gradlew --no-daemon --max-workers=1 :tools:content-lint:run` passed, 356 files / zero failures. No unit tests or test suites run.

A shared engine fix (`795f836c`, merged externally as `4988f047`) arrived during the session. It is preserved; this task did not author Kotlin changes.

Generic `burn` is 2 damage per tick, unlike the source’s turn-end Burn. Applying it during the long cinematic added unintended damage and could kill the dummy before Q finished. This revision uses cosmetic ignition instead; only the explicit coin damage mechanics deal damage.
