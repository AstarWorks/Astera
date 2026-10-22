# Sigil Rifle — reference and capture report

Worktree: `codex-sigil-rifle`. Reference: `-pOx9fo6A7M` (854×480, native 24 fps). Separate source audio muxed into ignored local compare cache; supplied originals unchanged. No Kotlin changes, unit tests, or test suites.

**Result: implemented and captured, but not full visual parity.** Each shown skill reached the four-iteration limit. The final pairs are the evidence; the remaining mismatches below are not waived as “built.”

## Reference identification

The old analysis conflated multiple attacks. Frame inspection distinguishes the rifle lunge (12.7–14.9), two standing discharges (15.4–18.9), and raised-rifle/kneeling discharge plus redirected seal impact (19.6–25.9). The 26.7–34.5 horned sequence repeats the third attack with an enhanced finish, consistent with the wiki kit's synergy variant; it is **not evidence of a standalone E.G.O cast**. This assignment is based on order, animation, and 1/2/2 wiki coins; the trailer does not show readable skill-name UI. No defense animation is identifiable. Intro/splash/logo frames are not combat.

| Slot / binding | Reference range | Wiki coins → authored direct damage beats | Range / movement | Final pairs | Capture iterations / status |
|---|---|---|---|---|---|
| S1 / left_click | 12.7–14.9 | 1 → 1, tick 21 | dash-melee; 2.6-block advance at ticks 17–20 | `build/pairs/sigil-rifle-s1.png` | 4 / partial visual parity |
| S2 / right_click | 15.4–18.9 | 2 → 2, ticks 20,54 | ranged narrow pulse; stationary | `build/pairs/sigil-rifle-s2.png` | 4 / partial visual parity |
| S3 / sneak_right | 19.6–25.9 | 2 → 2, ticks 71,102 | ranged discharge → target-local redirected impact; stationary | `build/pairs/sigil-rifle-s3.png` | 4 / partial visual parity |
| DEF / sneak_left | Not shown | wiki unavailable → 0 | inferred evasive backstep and ward | None: no source compare case | 2 side captures / inferred, no video parity claim |
| E.G.O / drop (Q) | Not shown | 2 → 2, ticks 30,60 | inferred charged round and seal impact; stationary | None: no source compare case | 2 side captures / inferred; ego_cost 100, resource_cost 0 |

No fake reference cases for absent skills. DEF and E.G.O use the same original crimson/charcoal/gold visual vocabulary. Existing S1/S3/DEF names retained; S2 renamed to an original name describing its corrected ranged behavior. New E.G.O name is original. No source character names or quotes in content.

## Measurement and validation

Each shown skill has `case.json`, `intent.json`, and a frame-based `notes.md` with pixel/body/block conversion formulas and hit-time arithmetic. Native frames and audio onset analysis are in ignored `tools/skill-compare/cache/`. Initial `./gradlew :tools:content-lint:run`: 345 passed, 0 failed. Final validator: **363 passed, 0 failed**. `git diff --check` also passes.

Conservative simultaneous-display bounds: S1 five-part rig + at most 36 trail/rim/edge surfaces + 5 impact elements = **46**; S2 rig 5 + pulse 7 + impact 3 = **15**; S3/E.G.O rig 5 + three seals (3 × (12 segments + 3 triangle edges)) + impact 3 + pulse 7 = **60**. Full seals and pulse do not overlap, so the expected actual peak is 53. DEF = 5. Ranged VFX resolve one forward-ray target; S1 impact geometry is caster-local, preventing per-enemy multiplication. Source diagram detail is deliberately bounded by the display cap.

S2 final capture sampled **13 block displays, 0 text displays** at peak (sampling can miss shorter peaks). The initial telemetry parser looked for lowercase `count:` while the server prints `Count:`, so its zero display counts are invalid and are NOT used as budget evidence. Health samples were valid. The final S3 count watcher terminated without producing data; its budget is supported by the conservative authored bound, not a live count. No large shape uses `glow_color`; rings are solid geometry, not Minecraft outline glow. Brightness is 15 on effect surfaces, with charcoal accents, translucent glass bloom, sparse red dust, crit and smoke particles.

Observed dummy health on the updated engine (sampled around the cast timestamps, not inferred from YAML):

| Capture | Health sequence | Direct hits observed |
|---|---|---|
| S1 iteration 3 and 4 | 200 → 192.9152 | 1 |
| S2 iteration 2 | 200 → 194.8832 → 189.76639 | 2 |
| S3 iteration 2 | 200 → 192.128 → 182.28801 | 2 |

During this session the worktree was externally fast-forwarded from `193dd2ab` to upstream `795f836c`, fixing skill damage re-entering item `left_click`. Early captures visibly demonstrate the bug (S2/S3/E.G.O unintentionally invoked S1). The isolated server was rebuilt before S1 iteration 3 and S2/S3 iteration 2; later captures use the fix. This task authored no Kotlin. Reload used `/astera admin reload all`; the shorter command in the authoring docs was rejected, corrected before the later captures.

## Fidelity limits and engine/tool gaps

- Content can animate a weapon rig, not the player's knees, spine, coat, or facial expression. S1 low slide and S3 kneel remain approximations.
- Three tilted, intricate red sigil diagrams are approximated with 12-segment circles and interior glyphs. No copyrighted texture assets added.
- The capture harness creates one target; actor intent keys do not stage the source's three-target group or allied cameo. Random secondary-target selection and lowest-HP/allied targeting are not reproduced by a single forward ray. Ray selection is reevaluated per beat; an enemy that leaves the line can evade the follow-up.
- No source-specific memory/sanity/unbreakable-coin mechanics. Existing poise scaling is a restrained approximation; no unsolicited shared status or Kotlin edits. Direct hit counts remain 1/2/2/0/2.
- Titles/screen_flash are visible to the caster (and can be addressed to players), not the spectator camera. The source's editorial gold frame, subtitles, extreme blur, and color grading are not world geometry.
- Source duration retained: S2 is 70 ticks; S3 is 126 ticks, exceeding the art-direction duration guideline to preserve the requested source timing. E.G.O effect recovery ends at tick 82. This is a deliberate fidelity tradeoff.
- Native 24 fps → 20 TPS quantization is ±25 ms; capture software rendering adds roughly ±1 tick. No claims of subframe synchronization.


## Final mismatch assessment

- **S1 (4 iterations):** connected red crescent, measured rifle length, short advance and one contact are reproduced. Player remains standing instead of sliding low; rifle finishing angle differs; red material is flatter/darker than the source. The block segments and vanilla dust cannot reproduce the painted coat/ribbon edges. The last unverified yellow impact-material tweak was reverted to the captured orange before commit. The weapon-pose mismatch is remaining authoring work at the iteration cap, not an engine impossibility.
- **S2 (4 iterations):** stationary aim, two timed direct hits, recoil, red pulse, muzzle plume and tail are present. Gold burst is narrower and less luminous; source blood-smoke spreads more broadly. The third capture suffered a delayed camera/skill start; final capture uses 10-second join and scene settle. Palette/shape still approximate; no exact-match claim.
- **S3 (4 iterations):** raised rifle, charged discharge, later first contact, three target seals, second hit and seal recovery are present. No kneel or three-target/cameo staging; seal interior is a simple triangle rather than a dense original diagram; dark red remains less luminous. The final tick-50 muzzle pair has no visible discharge yet, and the target close-up misses in this run. These timing/camera mismatches remain unresolved. Remaining limitations include both the documented actor/capture gaps and approximated art.
- **DEF:** no source/wikikit detail; conservative non-damaging evasive backstep with a small ward. Final side capture shows the ward, slight backward movement and no target hurt flash; runtime review only.
- **E.G.O:** no standalone source footage; wiki two-coin count implemented as shot then target impact, using the identity's observed palette. Final side capture shows two separate target hurt flashes, stationary firing, three seals and clean recovery; runtime review only. Lowest-HP/allied/random subtarget logic and sanity are not modeled.

Final source pairs are retained at `build/pairs/sigil-rifle-{s1,s2,s3}.png`; earlier iterations at `build/pairs/history/`. Copyrighted reference video/frames and the generated comparisons remain in ignored build/cache directories, not git. DEF/E.G.O side sheets are `build/vfx-shot/sigil-rifle-{def,ego}/side/sheet.png`.
