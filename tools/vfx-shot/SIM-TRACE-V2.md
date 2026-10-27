# astera-sim-trace/2

The file W5's `DefaultCastRunner` emits from a game-sim scenario (`Coin2CastScenario`), and the only input of the
skill-compare **PREVIEW** (`make skill-preview`, `tools/skill-viewer/scripts/trace-preview.mjs`). It carries what a
player would see, without a Paper server: **frame world poses per tick** and a **presentation object table**.
It replaces the v1 `plan.json` / `astera-sim-trace/1` for skill previews (ADR-0299). No dual reader exists.

Location: `tools/game-sim/build/sim-traces/v2/<skill-id>.json` (variant `couture-rapier-s2@crimson` -> `couture-rapier-s2@crimson.json`).
Validate with `python3 -I tools/vfx-shot/trace_v2.py <file>`; `python3 -I tools/vfx-shot/trace_v2.py --sample <out.json>` writes a
synthetic coin-2 trace for renderer work.

## Conventions

- Minecraft world axes: +X east, +Y up, +Z south, right-handed, blocks. Yaw in degrees as in Minecraft (0 faces +Z, 90 faces -X,
  -90 faces +X); never radians in the file.
- **Pose** = `[x, y, z, qx, qy, qz, qw]`: world position and unit quaternion (JOML `Quaternionf` component order x y z w), exactly
  `Frame.pos` / `Frame.rot` of `FrameTree.worldOf`. A frame's local +Z is its "forward" in `<frame>.forward`, local +Y is `up`.
- **Vec** = `[x, y, z]`. Ticks are integers, 20 per second. Tick numbers are absolute within the trace; `castTick` is the tick
  of the `cast` (the preview shows tick `t - castTick`).
- Every number is a JSON number (no NaN / Infinity). Floats are written with at most 6 decimals (keeps the file small).

## Shape

```json
{
  "format": "astera-sim-trace/2",
  "skill": "couture-rapier-s2",          // composed id, "<id>@<variant>" for a skin
  "item": "couture-rapier",              // the item the caster holds
  "tickRate": 20,
  "castTick": 5,
  "firstTick": 0, "lastTick": 100,       // inclusive; ticks[] has one entry per tick in this range
  "caster": { "id": "caster", "height": 1.8 },
  "victims": [ { "id": "enemy", "height": 1.8, "slim": false } ],
  "frames": [ FrameDecl, ... ],          // frame table (declared frames + built-ins), see below
  "objects": [ ObjectDecl, ... ],        // presentation object table
  "ticks": [ TickRecord, ... ]
}
```

### FrameDecl (one per frame that ever appears in `poses`)

```json
{ "id": "blade_a", "kind": "part", "parent": "shears", "item": "couture-rapier", "part": "blade_a" }
```

`kind` is the `FrameKind` name lower-cased: `world caster bone item part body enemy contact point`. `parent` is the parent at
**declaration** (re-parenting is visible in `events` and in `poses`, which are always world poses). `item` / `part` are present
for `item` / `part` frames. Built-ins `world`, `caster` and each victim root appear too. Points (`caster.right_hand`, `palm`)
are frames of kind `point`.

### ObjectDecl (the presentation object table; one row per `VisualHandle`)

`handle` is the integer id the `ISkillVisualGateway` returned (`VisualHandle.id`); `op` is the YAML op `id` that created it
(so skill-compare and the report can say "mesh `blade_a_mesh`"). `spawnTick` / `removeTick` (null = alive to the end;
`removeTick` is the first tick it is NOT drawn).

| `kind` | extra fields (all already evaluated) |
|---|---|
| `mesh` | `item` (item id), `part` (string or null), `scale` (number), `frame` (frame id the pose follows, for the reader; the pose itself is in `ticks[].objects`) |
| `plate` | `sprite` (string), `face` (Face), `size` (blocks), `alpha` |
| `line` | `thickness` (blocks), `color` ("#rrggbb"), `face` (Face) |
| `trail` | `ticks` (history length), `widthFrac`, `color`, `alpha` |

`Face` mirrors `PlateFace`: `{"type":"viewer_center"}`, `{"type":"viewer_vertical"}`, `{"type":"viewer_axis","axis":[x,y,z]}`
(axis in world space), `{"type":"plane","axisA":[..],"axisB":[..]}` (world-space axes of the plane; the plate is a quad in it).

### TickRecord

```json
{
  "tick": 12,
  "poses":   { "caster": Pose, "shears": Pose, "blade_a": Pose, "blade_b": Pose, "body": Pose, "enemy": Pose, "contact": Pose },
  "objects": { "3": { "pose": Pose, "interp": 1 }, "7": { "from": Vec, "to": Vec }, "9": { "root": Vec, "tip": Vec } },
  "events":    [ { "name": "release", "source": "shears" }, { "name": "hit", "source": "blade_a", "victim": "enemy" } ],
  "hitVolumes":[ { "hit": "id", "frame": "name", "type": "capsule|sphere|sector", ...geometry (world) } ],   // game-sim only: what each hit registration tested this tick (the preview overlay)
  "contacts":  [ { "piece": "blade_a", "victim": "enemy", "point": Vec, "normal": Vec } ],
  "particles": [ { "particle": "minecraft:crit", "at": Vec, "count": 8, "dir": Vec|null, "spreadDeg": 20, "speed": 0.2, "color": "#ff5fa2"|null } ],
  "sounds":    [ { "sound": "astera:skill.whoosh", "at": Vec, "volume": 1.0, "pitch": 1.2 } ],
  "damage":    [ { "victim": "enemy", "kind": "slash", "amount": 18 } ],
  "casterBody": null
}
```

- `poses`: world pose of **every frame alive that tick** (full set, not a delta). A frame removed from the tree disappears.
  `caster` is the feet position and body yaw (pitch 0); `enemy` / other victims likewise (F-5: root, `body`, `head` are
  `enemy`, `enemy.body`, `enemy.head` if the skill references them).
- `objects`: **only the objects updated this tick**, keyed by the handle as a string. An object that is alive and not listed
  keeps its last state. At `spawnTick` the spawn state must be present. Fields by kind: mesh / plate: `pose`, optional
  `interp` (display interpolation ticks, default 1); line: `from`, `to`; trail: `root`, `tip` (this tick's ribbon cross-section;
  the reader keeps `ticks` of history and ages it out).
  This is exactly what W5 passes to `ISkillVisualGateway.spawn*/updatePose/updateLine/pushTrailPoint`; a recording
  `ISkillVisualGateway` can fill it without evaluating anything.
- `events`: cast events raised this tick in raise order. `contacts`: resolved contacts (point/normal in **world** space here).
- `particles` / `sounds` / `damage`: the gateway calls of the tick, evaluated; informational (preview draws particle bursts as
  simple billboards and lists sounds in the strip captions, it does not run the vanilla particle simulation).
- `casterBody`: optional. When `BodyOps.CasterAnim` is active this holds the rig bone transforms in the caster root-local frame
  (rig frame as the skin boxes: x = the caster's left, y up, z forward, unit blocks, quaternion order x y z w):
  `{ "head": {"pos":[..],"rot":[..]}, "body": ..., "right_arm": ..., "left_arm": ..., "right_leg": ..., "left_leg": ... }`.
  `null` / absent = idle stance (the preview poses the arm so the held item stays in the hand, from `caster.right_hand`).

Top-level `diagnostics` (game-sim only): cast op diagnostics (UnknownPoint, ...) as strings; the preview shows them as warnings.

## What the preview does with it

1. Caster / victims are drawn as skin boxes at their root pose each tick.
2. Each `mesh` object is drawn as the item model (or the named part of it) at its pose. Model resolution is by convention, the
   file is the first that exists: `content/models/items/<item>-<part with _ -> ->.model.json`, `content/models/items/<item>.model.json`,
   `content/models/generated/items/<item>.model.json`; a missing model is drawn as a labelled box and listed in `meta.json warnings`.
3. `plate` = textured quad (sprite resolved like particle sprites; unresolved = flat tinted quad), oriented by `face`.
   `line` = ribbon between `from` and `to`, oriented by `face`. `trail` = ribbon through the pushed `root`/`tip` pairs.
4. The camera is the skill-compare case's intent camera (caster at the case origin, facing +X); the trace world is placed
   into it by one rigid transform (translate caster feet at `castTick` to the case origin, rotate yaw to face +X).

## Live-client (`make vfx-shot`) is independent

The real-client capture does not read this file. It needs the skill id (the coin is part of it: `couture-rapier-s2` = coin 2)
and runs `astera skill cast <skill> Caster --force` on the dev stack. The live server may log `[skill-trace] cast <skill> tick=<n>`
lines, which `vfx-shot.sh` copies into `trace.log` next to the frames; the tick stamp, not those lines, aligns frames to ticks.
