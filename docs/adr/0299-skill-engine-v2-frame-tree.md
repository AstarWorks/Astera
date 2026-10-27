---
status: Accepted
date: 2026-10-28
deciders: founder, Claude
supersedes: 0077, 0290
superseded_by: null
---

# ADR-0299: Skill engine v2: one frame tree, CEL expressions, kustomize-like composition

## Status

Accepted. Supersedes [[adr/0077-mechanic-targeter-phase-skill-architecture]] and
[[adr/0290-generic-vfx-primitives]] (their Status lines only were edited). Board SKL-08; SKL-06 depends on it.

## Context

The Mechanic/Targeter/Phase engine (0077) and the baked-coordinate VFX rig engine produced couture-rapier-s2
as a 367-line table of per-tick numbers generated outside the repo, which the founder rejected. Positions
were caster-relative or absolute, hit volumes and sounds had their own coordinates, and a skin could change
gameplay. Design source: `/tmp/claude-1000/skill-engine-redesign/design.md`; founder overrides:
`/tmp/claude-1000/skill-engine/spec-addendum.md` (2026-10-28).

## Decision

1. **One frame tree per cast** is the only coordinate system. Nodes (world, caster, bone, item, part, body,
   enemy, contact, point) hold a local pose in their parent. Operations: `worldOf`, `reparent(keepWorld)`
   (no position jump), named points (`blade_a.tip`). Effects, hit volumes and sounds attach to frame points,
   never to coordinates. Victim frames: root, `body`, `head` (F-5). Model points live in
   `content/frames/<item-id>.yaml` (F-3).
2. **Motion laws** are the only things that move frames: pose, spin, fly, path, follow, stick, reel;
   translation laws run before rotation laws.
3. **Hits are two-step**: a `HitVolume` (capsule, sphere, sector) defined on frame points; broad phase via
   an entity query, narrow phase by closest point to the victim hitbox. A `ContactEvent` carries point and
   normal in the victim frame (F-4) and drives every reaction.
4. **One skill file/set has two sections** over the same frames: `gameplay` (motion, hit, damage, status,
   impulse, freeze, schedule, events) and `presentation` (mesh, plate with two face modes, line, trail,
   emit, positional sound, victim_anim, remove). Types are two sealed families (`GameplayOp`,
   `PresentationOp`), so presentation cannot move frames or change contacts.
5. **Values** are literal, `${param}` or `= <CEL expr>` (F-7). CEL via cel-java, compiled once at load,
   evaluated per tick; cel, protobuf, guava and antlr are shaded and relocated (F-1). No Kotlin scripting.
6. **Handlers are lists of ops with unique ids**; conditions are a `when:` field (no duplicate map keys).
   Every patchable list entry has an `id`.
7. **Composition is kustomize-like (F-13)**: `kind: Skill | Patch` documents, one file with `---` or a
   directory; YAML anchors / aliases / merge keys allowed; strategic-merge patches (maps deep-merge, lists of
   maps merge by `id`, `$patch: delete`). `Patch` has `target`, `variant` (id becomes `<id>@<variant>`) and
   `patchKind: skin | balance`. A skin touching anything outside `presentation.*` is a load error; balance
   patches load only with the dev flag (F-8).
8. **Modifiers**: only `expose:`d params can be modified; stack order ADD, MULTIPLY, SET, clamp; snapshotted
   at cast start (F-12).
9. **Reload is per skill**: failure keeps the old definition; casts in progress finish on the compiled
   definition they started with.
10. **Old engine discarded (F-2)**: no coexistence, no shims; old `content/vfx` files and the VFX/mechanic
    engine are deleted. Scope is couture-rapier-s2 coin 2 only (F-10).
11. Code layout: pure domain `core/domain/skillengine/{frame,motion,hit,expr,model,op,modifier}`; application
    `core/application/skillengine/{compose,expr/cel,load,runtime}`; Paper adapters in `plugin/combat`
    `adapter/paper/skillengine/` (Bukkit only there).

## Consequences

### Positive
- Skill authors write structure (frames, laws, volumes, handlers), not numbers; range debuffs and skins
  follow automatically.
- Skins provably cannot change contacts (a game-sim scenario compares contact lists).
- Hot reload of one file, no classloader leak (no code is loaded).

### Negative / Trade-offs
- Large rewrite; the old engine and its content are deleted before other skills are ported (they are
  broken until ported; accepted by the founder).
- cel-java adds a shaded dependency set to the jar.

### Mitigations
- Only coin 2 is ported first; its game-sim scenario is the proof.
- Relocation of cel / protobuf / guava / antlr avoids clashes with Paper.

## Alternatives Considered

- **Self-written expression language**: more code to own, no ecosystem; rejected (F-1).
- **Kotlin script for skills**: hot-reload leaks and no sandbox.
- **Two files (skill + look)**: contract between files is harder to keep than two sections of one document.
- **Keep the old engine in parallel**: doubles the surface and invites shims (F-2).

## References

- 関連 ADR: [[adr/0077-mechanic-targeter-phase-skill-architecture]], [[adr/0290-generic-vfx-primitives]], [[adr/0266-board-and-tracks]]
- Design: `/tmp/claude-1000/skill-engine-redesign/design.md`; addendum: `/tmp/claude-1000/skill-engine/spec-addendum.md`
