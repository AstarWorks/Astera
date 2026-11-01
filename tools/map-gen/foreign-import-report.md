# anni-foreign — Highmarch import isolation (2026-11-01)

## Root cause and evidence

`world`, stage `annihilation-arena-slot-2`, origin `(4096,0)`; sparse non-air paste plus
successively shrinking persisted bounds retained old `annihilation-arena` frost islands,
bridges and beacon blocks outside the newer map. This is historical geometry inside a reused
slot, not an overlap between today's simultaneously allocated stages.

Read-only dev evidence (VM `astera@192.168.14.3`):

- `/var/lib/docker/volumes/astera-dev_game-data/_data/logs/latest.log`: 12:05:49 UTC sweeps
  13 recorded slots; 12:07:36 Highmarch ready in slot 2 (1,974,961 blocks); 12:30:49 warm
  Highmarch ready in slot 14; 12:37:36 slot 2 repair reports success.
- A read-only copy of overworld regions `r.{7,8}.{-1,0}.mca`, taken after that repair,
  still contains **73,118 non-air blocks** near slot 2, x=3893..4299, z=-205..229.
  Counts include 48,819 deepslate, 12,516 packed ice, 6,450 snow blocks, 909 spruce
  planks, 62 sea lanterns and a single red stained glass at `(4096,100,-187)`.
- That footprint and palette identify the pre-relief **annihilation-arena**, not
  siege-isles-loyal. See `git show f32ac73ef^:content/stages/annihilation-arena.yaml`:
  frost theme, seed 20261025, bases `(0,-166)`, `(143,83)`, `(-143,83)`, and outposts
  `(177,-101)`, `(-177,-101)`, **`(0,203)`**. The matching real-client camera reconstruction
  from anni-inworld identifies the pictured island as the **west** flank, local
  **`(-177,64,-101)`**, world centre **`(3919,64,-101)`**, beside the red/yellow towns
  and west Highmarch outpost `(-85,-50)`. This corrects the earlier south-flank
  identification; both legacy islands existed in the sampled residual data. Its straight illuminated
  spruce bridges run to the blue/yellow bases. Shape/materials come from
  `ArenaIslandGenerator.bridge` (line 460) and frost terrain/decoration (line 1404 onwards).
- All current procedural/import/debug stages share `world`; `PaperArenaWorld.placed`
  (line 248) shifts each into a **2048-block pitch**, with plans restricted to +/-512.
  Temple and debug stages occupy separate slots. Historical `world: arenas` content
  predates this placement path; today's runtime ignores that old arrangement.

The ADR-0281 decoder (`ArenaImportYamlConfig.kt:42`) omits token 0. The generator's
imported plan contains only non-air cells (plus iron generator pedestals), and the old
`ArenaPaster.paste` only wrote that map. Boot persisted only the latest non-air bounding
box; repairs used its +/-24 horizontal and limited vertical margin. Consequently a map
shrinking from the old frost footprint to compact islands lost the record of outer islands;
Highmarch later pasted over those survivors without clearing its air holes or outer margin.

## Screenshot interpretation and remaining uncertainty

The image was inspected and compared with a fresh production-plan offline isometric preview
(`/tmp/anni-foreign/iso.png`). Highmarch itself authors tall grey chapel/spire masonry and
an L-shaped wooden lookout approach: `highmarch.py:359`, `:399` and `:918`. The upper-left
masonry/wood silhouette is consistent with that structure; it must not all be classified as
foreign merely because it looks unfinished. The architecture-quality task owns its appearance.

The on-disk **foreign translucent block that can be identified** is the old red beacon's
red stained glass at local `(0,100,-187)` (beacon underneath at y99), from
`ArenaIslandGenerator.tower` (`:897`). However, there is insufficient camera-coordinate
evidence to identify that block with the *pale square over the grass* in the screenshot.
No barrier, structure_void or light block exists in the imported plan or the sampled
residual block palette. Those invisible marker types are therefore not supported as its
source. The white sky square near the rim is visually consistent with the sun; neither it
nor the pale square can be conclusively classified from this single screenshot. A live
camera-position capture after redeploy remains necessary; do not report either as proven
removed. This worker never executed commands inside or modified/restarted a live dev container.

## Fix and checks

- `PaperArenaWorld.kt:217`: clear the entire permitted slot **+/-512 x/z, minHeight through
  maxHeight-1** on every initial import, replenishment and fallback rebuild. This includes
  a 300+ block Highmarch margin and every known old island without reaching a neighbouring
  slot. Keep the established disjoint slots: a new world per map adds lifecycle/teleport
  changes without improving isolation over these already disjoint owned volumes.
- `PaperArenaWorld.kt:221`: clear -> paste -> verify -> Ready. Persist the full sweep volume.
  A failed startup sweep retains manifests and blocks replenishment, including queue-triggered
  attempts; failure no longer silently enables the warm pool.
- `ArenaPaster.kt:59`: verify every non-air cell against the precise generated plan, including
  implicit air holes/margin; count matched cells to detect missing blocks. Report world,
  coordinate, expected and actual state on mismatch. Heightmap skipping avoids reading empty
  space above the highest non-air cell without weakening full-height coverage.
- `ArenaPaster.kt:166`: invalid Bukkit block data fails the paste instead of skipping cells.
  `SlotManifest.kt:12`: malformed manifests and read/write failures are no longer swallowed.
- `HighmarchScenario.kt:32`: **HighmarchImportIsolation** loads real shipped RLE through
  production decoders/generator, checks every cell against the import/pedestal overlay,
  rejects debug marker states and checks legacy island/beacon cells are absent.
- `AnnihilationPlaythroughE2eTest.kt:159`: **Highmarch import leaves no legacy frost islands,
  bridge or high beacon** checks 11 legacy, margin and build-height cells in the real match.
  Manager runs it in the batched e2e; worker compiles it only.
- No Highmarch terrain, feature anchors, stage YAML, ore YAML or import data were edited.

Verification evidence lives under `/tmp/anni-foreign/`: generator exact regeneration passed;
HighmarchScenario passed **730 routes, zero failures**, 75 ore pockets and radius-17 wave ground;
full compile/e2e test compilation/content-lint passed (`compile-final.log`). Final core/e2e compilation passed (`compile-scan.log`);
final e2e compilation passed (`compile-e2e-last.log`), and remote Konsist passed
(`architecture-confirm.log`, BUILD SUCCESSFUL, runner rc=0). No e2e, unit test or Gradle check ran.

## Manager/operator steps (not executed by this worker)

No dev world or player-data wipe is required. Force the backend restart path once so the new
builder instance and startup gate take effect; a content-only reload/hotswap leaves existing
Ready slots intact. From the manager's merged integration worktree, with `HEAD` at the reviewed
integration SHA, run through the existing deploy lock:

```sh
flock /tmp/claude-1000/astera-dev-deploy.lock env ASTERA_HOTSWAP=0 ASTERA_RESTART_REASON=anni-foreign-slot-sweep make dev-deploy
./scripts/dev-autodeploy.sh mark-deployed "$(git rev-parse HEAD)"
```

Wait for `Arena 'annihilation-arena' ready in slot ...` (publication now implies full-volume
verification). A sweep/paste/verification error must be investigated before any founder check;
retain `plugins/Astera-Core/arena-slots.txt`, do not delete it to bypass a failure. The next
Highmarch allocation clears its whole owned volume, including old records' omitted remnants.
Run the manager's one batched `make e2e`, then inspect the snow island/bridge area in game and
capture the camera coordinates for the two uncertain squares. Full-slot clearing/verification
adds warm-up work; measure live readiness time before marking verified.

## Documentation/code discrepancies found

1. The previous area-spec line said stages shared a world at +/-300 offsets and one match at
   a time; runtime uses simultaneous 2048-pitch slots. The current-state entry and the obsolete
   summary line now agree with the runtime.
2. `ArenaBuildPlan` documents absent cells as air, but the old sparse paste did not enforce
   that invariant. Clear/verify now enforces it in the slot volume.
3. `content/worlds/lobby.yaml` describes a world named `lobby`, but its `id` is `world`;
   runtime placement also uses `world`. This unrelated comment needs its owner to correct it.
4. The old paster comment claimed invalid states must be skipped to save lobby NPCs, although
   this class is arena-specific. That skip was removed; invalid plans cannot become Ready.
