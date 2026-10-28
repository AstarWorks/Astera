# Area: skills and VFX (Limbus track)

Owner track `wp/skills-vfx`. Items: Board prefix `SKL-`. Sources: handoff `limbus-skill-team` (2026-10-25), `docs/contributing/limbus-skill-reproduction.md`, `docs/reference/vfx/`.

## 構想 (source)
- Reproduce Limbus Company skills in Minecraft: motion first, then VFX (Display core + particles), then sound.
- Founder 2026-10-25: motion roughly the same as the reference FIRST; Minecraft-look design later; first person out of scope; match the third-person side view first.
- Work split by part: modeler, weapon motion, character body motion (skin rig, ADR-0250), BlockDisplay VFX core, particles, sound.
- **Done = reviewer PASS + founder approval.** The reviewer judges motion match per beat against the reference and the founder's words, never particle counts.

## 現状
- ADR-0304: `tools/item-art` は `sprites.yaml` の型付き YAML node graph を CPU float RGBA で描画する。旧 `draw:` 30 件は graph に移植済み（旧レンダラーの全フレームとピクセル差分 0）、sprite 固有 Kotlin painter / `draw:` decoder は削除。PNG 横連番・font・runtime metadata の形式は維持。`sprite-preview` は frames / contact sheet / GIF / ノード中間出力を `build/` に生成する。[制作手順・ノード一覧・3 レシピ](../../contributing/vfx-team/sprite-graph.md)。実クライアントの見た目はこのツール確認では未検証。
- ADR-0305: `content/shader-patches/effects/<id>/` composes named shader hooks and validates marker allocation. Pack mesh ops select `shader_effect`; three playable examples `content/vfx/demo-shader-{dissolve,uv-flow,emissive-pulse}.yaml` cover dissolve, procedural UV flow and emissive pulse. Markers combine isolated model tint with brightness (13,11); existing skin/weapon rig encodings are unchanged. Compile/pack and capture evidence are tracked in the implementation report; integration tests await the manager window.
- 14 `sample-*` graph demos now have playable skills: 11 viewer-facing plates plus 3 animated mesh shader combinations (`sample-dissolve-sigil` → `dissolve`, `sample-energy-orb` → `emissive-pulse`, `sample-heat-haze` → `uv-flow`). Tags are strictly kebab-case string lists. All 14 samples were captured in a throwaway real-client stack (40 ticks, side, no skipped ticks); the gallery browser integration requires and fetches every real-client sheet. The sprite gallery serves this checkout and exposes shader ids and real-client side sheets; atlas mesh animation uses client-global phase, with a fixed side-facing surface.
- `make live-view SKILL=<id>` provides a real Java-client browser stream with isolated game audio, Cast/Recast, content reload, caster/side/compare cameras, HUD, PNG snapshots and recent MP4 clips; see `tools/live-view/README.md`. Capture only, one vfx-shot slot for the session lifetime.
- 151 skill definitions plus 3 playable shader demos; per-skill presentation is engine-v2 YAML (the former standalone VFX library is no longer shipped) (bastion, couture, crimson, edict, ember, gossamer, orrery, sigil, vendetta, verdict). Tools: `make vfx-shot`, `make anim-shot`, skill-lint, skill-compare page.
- bastion S1 content r1-r26 rejected by the founder ("S1 got worse": near-zero particles, wrong swing/roll, 26 rounds of technical-only checks).
- ADR-0276 one-sword performance runtime (IPlayerPresentation lease, body planner, weapon on main hand) and skill-lint are merged on the Limbus branch but NOT verified in a real client; side effect: pose pinned STANDING during a skill.
- The other 9 families have never been reviewed against this standard (推測). Sound quality for these skills remains unreviewed (batch captures are silent); live-view verifies audible real-client capture for bastion S1. 25 of 84 skills have no sound.
- Pipeline speed-ups proposed (warm vfx-shot stack, keypoint annotation -> IK, skill-viewer preview, local client particle sim) wait for a founder go.

## 理想形
Each skill is a short performance: caster body motion, one real weapon, Display-core VFX, finishing particles and sound, all driven from the reference beats. Reviewer rows are tied to per-beat reference frames; captures are client-faithful (never an approximation).

## 未決 (founder, unanswered)
- First-person empty hand and the global instant hotbar swap.
- ADR-0274 (server-wide particle reskin) and ADR-0275 (held-sword shading) landed without approval: keep or revert?
- When and by whom are the other 9 families re-reviewed?

## Governing ADRs
0231, 0238, 0239, 0241, 0250, 0274, 0275, 0276.
