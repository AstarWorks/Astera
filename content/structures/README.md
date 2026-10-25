# Authored arena structure parts

ADR-0252. These are original, repository-authored full-cube builds; no external schematics, paid services or AI assets.
The server and `tools:arena-preview` load the same strict YAML through `ArenaStructureContent`.

```yaml
id: small-wall
boxes:
  - { min: [-4, 1, -1], max: [4, 6, 1], block: "@wall" }
  - { min: [-1, 1, -1], max: [1, 4, 1], block: "minecraft:air" }
```

Each inclusive box fills `min .. max` in local `[x,y,z]`; later boxes replace earlier boxes. Explicit air carves an
opening. Coordinates are bounded to ±128, boxes to 100,000 cells, and parts to 512 boxes. Use full cube IDs or
`@slot`; block-state strings, stairs, slabs, entities and block entities are deliberately unsupported. The first
walkable floor normally sits at local y=0, with feet at y=1. Ordinary solid blocks form climbable one-block steps.

Stage placement:

```yaml
layout:
  theme: frost
  structures:
    - part: castle-tower
      x: -150
      z: 700
      y: 0
      rotation: 90
      palette: { team: minecraft:red_concrete }
```

`x/z` are world coordinates, `y` is relative to `surface_y`. Rotation is clockwise about local `(0,0)`:
90° maps `(x,z)` to `(-z,x)`. Every full-cube material is rotation-invariant. Palette overrides replace the stage
slots for that placement. Theme slots: `ground`, `soil`, `rock`, `wall`, `deck`, `log`, `leaves`, `light`.
Team-bearing castle parts additionally require a `team` override. Unknown parts or slots fail the plan.

Generation order: terrain, bridges, team pads, objective anchors, reserved roads, authored parts, random cover and
foliage. Parts own their footprint; random features cannot cover them. Avoid carving objective cells or blocking
spawn headroom. The preview validates all shipped spawns, objective approaches, generator pedestals, both bridge
directions, base-to-base routes and tower/keep climbs with the production A* implementation.

Shipped parts: `castle-wall` (battlement and courtyard stair), `castle-gate` (open ground-level arch),
`castle-tower` (interior stair and lookout), `castle-keep` (objective hall and upper gallery), `bridge`, `ruin`.

Run `make arena-preview`. Outputs are `build/arena-previews/<stage>-top.png`, `<stage>-iso.png`, and `report.md`.
The report measures pure plan generation/digest and direct versus hierarchical A* node counts, with a 5000-node
cap per local search. Paper build logs supply actual placement ticks and maximum slice time; the offline tool does
not pretend to measure Bukkit or chunk loading. Bump `layout.marker_version` and restart after editing parts to
force rebuilding a cached stage (the plan digest also detects changes on the next boot).
