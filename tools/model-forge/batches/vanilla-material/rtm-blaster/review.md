# rtm-blaster — vanilla materials

The 177 original cuboids, their coordinates/rotations and every display transform are
unchanged. No cuboid exceeds 16 units; no splits were needed. Only texture references
and UV crops change. All materials use native block resources, one texel per local
model unit. Seed: 20261023. No custom or Mojang PNG is shipped.

| Parts | Catalog material |
|---|---|
| Receiver, scope body, barrel, forearm and energy housing | `metal.netherite_block` |
| Bevels, collars, rails, clamps, screws, selector and trigger | `metal.iron_block` |
| Stock core, heel, neck and cheek comb | `wood.stripped_dark_oak_log` |
| Grip, grip ribs, buttpad and buttplate ribs | `cloth.black_wool` |
| Recesses, dark muzzle and ports | `concrete.black_concrete` |
| Energy coils, violet channel surround, identity stripes and lenses | `gem.amethyst_block` |
| Pink core, battery windows, indicators, bore and coil highlights | `concrete.magenta_concrete` |

## Visual review

Opened the same-camera eight-view before/after boards, the player-context board,
26-direction orbit/pole sheet and 16/32/64px inventory sheet. The first material pass
made the brown stock netherite; the final pass restores its wooden role. Grain follows
the longest local stock axis. Small metal parts use low-detail crops rather than
coarse patterned blocks. Iron frames separate the violet chamber from the dark body;
the stock, scope and barrel remain identifiable at 32/64px. At 16px the scope/coil
details merge, while the long weapon silhouette and purple muzzle remain readable.

- Silhouette/ratios: yes, original cuboid geometry and transforms compared equal.
  This is a material regression review against the original asset, not a new concept.
- Bevels/joins: unchanged; eight-view and sweep images show no new floating pieces.
  Existing layered facet intersections remain (for example e0017–e0032 and e0114–e0133).
- UV coverage/orientation: yes, native crops cover all faces; no stretched atlas regions
  or new >16-unit tile seams. Stock grain is longitudinal.
- Contexts: both first-person hands leave the central aim clear; third-person side
  shows a forward muzzle and hand at grip. Ground/frame/GUI keep original placement.
- Verifier: wood replaced the initial metal stock without changing its outline;
  the dark receiver, silver rails and violet chamber remain distinct in other views.

`contexts.png` is the player-context renderer's nine-view board (rather than Forge's
approximate hand guide). `angles.png`, `inventory.png` and `cameras.json` retain extra
review evidence. Conservative overlap/contact warnings predate this material pass;
this is not a complete z-fighting audit. Before and after validation both report zero
errors, 543 AABB overlaps, 27 contact candidates and one component warning.

## Native client review

Opened `client.png`: unedited 1280×720 Xvfb root capture from the real 26.3 client.
The wooden stock, dark netherite receiver/scope, iron rails and purple chamber resolve
correctly. The stock reaches the lower-right edge at the unchanged first-person
transform; the forward muzzle and scope stay readable and the central aim is clear.
No model/texture or mixed-atlas errors appear in the log. Offline account/Realms
authentication, unavailable audio and the existing `end_of_frame` post-effect message
are unrelated capture-runtime messages. This is a minimal capture pack, not a deployed
production ZIP. Native third-person animation/FOV variants remain outside this capture.

## Reproduce

Save the original model from this commit's parent and run from repository root:

```sh
git show <parent>:content/models/items/rtm-blaster.model.json > build/rtm-blaster-before.json
python3 tools/model-forge/batches/vanilla-material/rtm-blaster/remake.py \
  --project build/rtm-blaster-fresh --before build/rtm-blaster-before.json
python3 tools/model-forge/context_preview.py --out build/rtm-blaster-contexts rtm-blaster
```

The remake follows the pilot's Forge import/query/checkpoint/batch/reimport/export
sequence. A second fresh replay produced the identical model SHA-256. To regenerate
the player board, convert `build/rtm-blaster-contexts/rtm-blaster-board.jpg` to PNG.
Generate the native pack with `capture-pack.py --out <fresh-pack-dir>`. Launch the
installed portablemc 26.3 runtime with `capture-client.py`, holding the entire
launch/capture under `flock /tmp/claude-1000/astera-mc-client.lock`. Xvfb `-displayfd`
allocates an unused display; the game directory is `build/rtm-blaster-client`.
Prepare a commands-enabled creative superflat save named `Blaster Material Review`
under its `saves/` first. This run copied the already isolated greatsword capture's
superflat save to that unique local directory (excluding `session.lock`), then
cleared the inventory, gave the blaster, teleported to 0/-60/0 and set noon/clear
weather. It does not connect to an Astera server. The script uses `--join-world`,
waits for the join log, sends those commands and captures Xvfb root without edits.

## Remaining risks

- Native materials provide color/texture, not emissive light or shader bloom.
- Original layered facet overlaps remain; finite camera sweeps cannot establish
  every runtime animation/FOV combination.
- Production pack build, server deployment and integration checks are manager work.
  No Gradle, tests, E2E, Docker, server process or push was run.
- Catalog wishes: none. Existing entries cover this pass.
