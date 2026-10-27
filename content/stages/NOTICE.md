# Third-party map attribution

The stages below are built from maps published under **CC BY-SA 4.0**
(https://creativecommons.org/licenses/by-sa/4.0/). The block data in `content/arenas/<id>.yaml` is an adaptation of the
original world and is itself licensed **CC BY-SA 4.0** (ShareAlike); the stage YAML files that place objectives on it
and the rest of this repository keep their own licence. See ADR-0281.

All maps were modified: converted from the pre-1.13 world format to modern block states, moved vertically so the spawn
floor sits at y=64, signs, banners, skulls and pistons dropped, stray decoration removed, bridges over void gaps and
stone-brick plinths for the star generators added. Objectives, spawns and generators are Astera's own.

| Stage / arena | Title | Authors | Source (pinned commit) | Licence evidence |
|---|---|---|---|---|
| `temple-trouble` | Temple Trouble | Salgadoh (contributors: TinyPigMX, diKali) | https://github.com/OvercastCommunity/CommunityMaps/tree/17a7432a670589e153f5b07c933557dabca3d981/ctw/temple_trouble | same repository, same `LICENSE.md` |

Per-file credits (title, authors, source, licence, modifications) are repeated in the `credits` block of each
`content/arenas/*.yaml`. The converter and per-map configs are in `tools/map-import/`.
