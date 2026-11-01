---
status: Accepted
date: 2026-10-31
deciders: founder + Claude
supersedes: null
superseded_by: null
---

# ADR-0314: Frame grammar: a skill draws everything from frames (origin + v, w, s) placed by coefficients

## Status

Accepted

## Context

Skill YAML placed its particles, displays and the held weapon with per-row `anchor` + `offset` triples and the rig's own
`swing:` keys (`roll`, `edge`, arm-frame angles). Three readers computed three slightly different bases (particles under
ADR-0312's anchor frame, displays under the anchor's rotation, the Paper skin rig under the arm), and a hand-authored
skill like `bastion-greatsword-s1` carried offsets such as `[-0.0124, 0.2653, -0.1788]` copied out of a model file. The
founder's verdict (2026-10-31): 「全部直そう。美しいconfigになるようにするべきだ。configが美しければ多分大体大丈夫だ」,
「originに対して、v,w,sでv方向に0.5,w方向に-0.1とかでいいじゃんか。そしてv,w,sは任意の角度に変換や回転掛けられる」,
「パーティクル、VFX、スキンアニメ、それぞれで使う基準を共通化…計算も減る」. The concrete symptom:
「剣がなんか平たい所で殴るようになっている。刃部分で回転ではなく、なんか平たく殴っている」 - the greatsword cut with its flat
because the swing was keyed in the arm frame and the blade plane was a separate `edge`/`roll` correction.

Constraints: Phase 1 pre-release posture (no dual decoders, no `@Deprecated`), ADR-0303's anchor vocabulary (`caster`,
`contact`, projectile bodies) stays, Konsist boundaries (domain is Bukkit-free), one evaluation shared by the particle,
display and skin-rig readers, and ~70 shipped skills still written in the anchor/offset form that must keep decoding
until each is rewritten (the Understanding file lists this as a fan-out).

## Decision

A skill declares `frames:` at the top level. A frame is one origin (`caster`, `caster.<eye|feet|body|center|hand_r|hand_l>`,
a projectile op's body, or `victim.<point>` with `on: event.victim, each: true` for one instance per victim) and three
vectors `v`, `w`, `s`, each a named world direction (`aim up down forward back left right incoming velocity`), a literal
`[x, y, z]`, or the cross of two others (`v x w`). The basis need not be orthogonal; a coplanar basis is a decode error
naming the frame and the field (a live basis that collapses at a tick is a named fault). A frame moves by keyed tracks:
`move` (origin, in its own coefficients), `rotate` (about its own `v`/`w`/`s`, keys composed in list order about the
body axes - the list is the chain, so a `v` key listed after the `s` sweep rolls about the swept blade; keys of one axis are in tick order, axes interleave freely, but a sweep key only cuts in the blade plane while no later-listed key of another axis is turning at the same time - `from` only for a tick-0 pose, `reset: true` slerps the chain back to rest) and `scale`, each key `{at, for,
to, ease}`. Every drawn thing (`particle`, `particle_def`, `display`, `sound`, a `projectile` origin, the `rig`) is placed
with `frame: <id>` and `pos: {v, w, s}` coefficients (`to:` + `samples:` spreads a segment). The runtime evaluates every
live frame instance once per tick (`TablePlayer.frameEvaluations`) and every reader shares that state. The rig on a frame
aligns the part's `long` axis with `v` and its `edge` with `w`, grip on the origin: a swing is a `rotate` about the
frame's `s`, so the blade plane is the cut plane by construction and `roll`/`edge` do not exist. The skin-rig hand is the
frame origin for `caster.hand_r`. A hand-authored placement has at most two decimals (contentLint, `frames:` skills).

Three more forms keep the whole skill inside the frame vocabulary, so `left`/`up`/`forward` never appear in a frame
skill:
- A frame may also be rooted on a block contact: `on: event.block, each: true, origin: contact` gives one instance per
  block hit, with the directions `incoming` (the projectile's travel) and `normal` (the face) available to its axes.
- A `display` on a frame is oriented by the frame: `plane: [a, b]` (a, b of `v w s`, optionally `-`) lays its plate
  with width along `a` and height along `b` (normal `a x b`); absent, the plate faces `v`. Its `rotate:` turns about the
  frame's own axes (`axis: v|w|s`); caster-local axis names are a decode error on a frame display and vice versa.
- `lines: {<id>: {frame, from: {v..}, to: {v..}}}` names a segment of a frame once; a particle row reads it with
  `along: <id>, samples: n`, so one blade line serves every trail, gather and sheath row.

## Consequences

### Positive
- One basis for particles, displays, projectiles and the held weapon; one evaluation per tick instead of three readers.
- Edge-leading swings are structural: `rotate: [{about: s, ...}]` cannot cut with the flat.
- Per-victim frames express 「全員に被弾したときに出すパーティクルや3Dモデル」 as `on: event.victim, each: true`.
- Config reads as geometry: `pos: {v: 1.7}` is the tip, `pos: {v: -0.3}` is the entry side of a wound.

### Negative / Trade-offs
- Two grammars coexist while shipped skills are rewritten: `anchor:` + `offset:` for the ~70 untouched skills, `frame:` +
  `pos:` for the pilots (`revolver-shot`, `bastion-greatsword-s1`) and everything new.
- ADR-0312's world-box `offset` spread cannot rotate with a frame: a spread along a turned axis needs `to:` + `samples:`.
- A `shift`-CEL that grows a ring per instance is not a literal `pos:`; such rows need a `scale` key on their own frame.
- A per-victim instance lives for the cast and its keys count from its first reference, so a keyed per-blow effect (an opening
  ring) is one frame per blow (`burst1`, `burst2`); only a keyless frame (`wound`) is shared by every blow on that victim.
- Body rotations do not commute: `to: 0` on each axis is not the rest pose; authors must use `reset: true`.

### Mitigations
- `FrameRefCheck` makes an undeclared `frame:` a compile error; the decoder refuses `offset`/`shift` next to `frame:`.
- Game-sim scenarios (`FrameGrammarScenarioTest`, `FrameRigScenarioTest`) pin the one-evaluation rule, per-victim
  instances, keyed positions and the edge-leading invariant on the real s1 content.
- The fan-out (rewrite the remaining anchor/offset skills, then delete `offset:`/`swing:`) is a Board item, not a shim.

## Alternatives Considered

- **Keep `anchor` + `offset` and add `rotate:` per row**: three readers stay, the blade plane stays a correction.
- **Key swings in the arm frame with an `edge` angle** (the previous `swing:`): the founder's complaint exactly.
- **Orthonormalise v, w, s silently**: hides authoring mistakes; the founder asked for no silent fallbacks.
- **Zeroing angles as the reset**: not the rest pose under body-axis composition; a reset key is explicit.

## References

- 関連 ADR: [[adr/0303-natural-skill-schema]] (anchors stay), [[adr/0312-particle-frame-basis]] (particle data in the
  anchor frame; world-box spread limit noted above).
- Understanding: `/tmp/claude-1000/skill-engine/understanding-frame-grammar.md` (session scratch; the decisions are here).
- Code: `kernel/domain/.../skillengine/frame/grammar/FrameGrammar.kt`, `kernel/runtime/.../load/FrameDeclDecoder.kt`,
  `kernel/runtime/.../load/grammar/FrameRefs.kt`, `TablePlayer` frames section.
