# Knight sword palette restoration

Original palette reference: `36743b70:content/models/items/knight-sword.model.json`,
rendered through Forge as `before.png`. Its atlas has silver-blue steel
(#96acb4 / #cbdde0), gray-blue fuller (#718493), warm gold (#d99c30 /
#f4cb59), reddish leather (#4c261a), and dark binding (#281612).

| Parts | Vanilla pattern | Tint |
|---|---|---|
| Blade web, ricasso, point | iron_block | #b0c7ce |
| Edge bevels, fuller lips, wear highlights | iron_block | #e0f5f6 |
| Inset fuller | iron_block | #8196a6 |
| Guard arms/center, collars, wheel middle | iron_block | #e9af38 |
| Gold bevels, facets, curls, rivets, bosses, border | iron_block | #ffda71 |
| Rivet bezels | iron_block | #c08532 |
| Leather grip | white_wool | #602f21 |
| Diagonal leather binding | white_wool | #301d17 |

Tints compensate for multiplication and separate edges from the blue-silver web.
Fine wool crops give the leather its wrap texture. The gold/silver/brown identity
remains distinct from the greatsword's blue/crimson palette. No custom PNGs or
Mojang texture sprites are shipped.

## Review and verification

Opened before/after eight-view boards, contexts.png, contexts-faithful.jpg,
orbits.png (26 directions), and inventory.png (16/32/64px). Blade edges and gray-blue
fuller remain distinguishable; gold guard/pommel and reddish grip read at small GUI
sizes. Side, back and underside retain the original joins and silhouette.
No new floating pieces or visible fighting faces were observed. Existing cuboid
intersections remain outside this material-only task.

`verification.json` records exact HEAD geometry and display equality (117 elements,
unchanged face directions), eight deduplicated tint entries, identical Forge
export/import, zero exported PNGs and zero validation errors. Conservative overlap
warnings describe retained geometry. Operations and warning counts are preserved in
operations.json. The historical reference is never used as the geometry source.

Reproduce from the repository root with a fresh ignored project directory:

```sh
git show 36743b70:content/models/items/knight-sword.model.json > build/knight-sword-original.json
python3 tools/model-forge/batches/vanilla-material/knight-sword/remake.py \
  --project build/knight-sword-tint-replay --before build/knight-sword-original.json
flock /tmp/claude-1000/astera-mc-client.lock \
  python3 tools/model-forge/batches/vanilla-material/knight-sword/capture-client.py \
  --world-source '<isolated creative superflat world directory>'
```

## Native capture

The capture pack follows ResourcePackBuilder: remove `astera_tints` from the cuboid
JSON and emit the indexed RGB values as `minecraft:constant` sources in the item
model definition. All face tint indices and material references are preserved.
This is a minimal builder-equivalent pack, not a production ZIP build.

The full portablemc 26.3/Xvfb launch, capture and shutdown holds the shared client
lock. Its unique game directory is `build/knight-sword-client`; Xvfb allocates an
unused display. The isolated copied world uses creative mode, commands enabled,
noon/clear/peaceful, FOV 70, bob disabled and 1280x720. `client.png` is the native
unmodified root screenshot with the equipped sword and hotbar.

Opened `client.png`: silver-blue steel, a darker fuller, warm gold fittings and
reddish/dark leather wraps render correctly in first person and the hotbar. Final
client log has no model, texture, mixed-atlas or tint-loading errors. Offline
authentication/audio and a missing end_of_frame post-effect message are unrelated.
The final pack's cuboid and constant tints were compared directly with source.
The source world must be client-created; a Paper-exported world's dimension data
was rejected before the successful capture.

## Remaining limits

Native capture covers first person and hotbar. Swing/use, shaders, glint and other
native contexts remain unverified; offline player lighting is illustrative.
Multiplicative tints cannot brighten black or provide emission. Production pack
build/integration verification remains with the manager. No tests, Gradle, Docker,
servers, live containers or push were run. Catalog wishes: none.
