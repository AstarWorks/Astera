# Understanding: <skill / vfx / model / sound>

How to fill: watch the whole reference 3 times, then list objects that exist in the world. No cell stays empty
(write `-` only with a reason). Count objects, not frames; the ledger has no frame wording, frames go in the evidence column only.
Unknowns are mandatory. For Limbus skills this file is the v4 `inventory.md`; gate 1 publishes it with
`make skill-round SKILL=<s> FILE=<inventory.md>` (see `docs/contributing/vfx-team/beat-sheet.md`).

Medium rule: a solid thing that stays and is attached to something (a ring, a fireball) = 3D object; a slash or arc on a
plane = 2D sprite; many short-lived bits = particles.

Frame to tick: the reference is 29.97 fps, the game 20 tps, so tick = round(frame x 20 / 29.97) (0.667 ticks per frame;
t_s x 20 = tick). Case beats are `[{t_s, label, weapon, caster_move, vfx, notes}]` in
`tools/skill-compare/data/<case>/case.json` (`tools/skill-compare/README.md`).

## 物の台帳
| # | 物 | 数 | 由来 (何から出る) | 付く先 | 色 | 大きさ | 出現 → 消える | 動き | 媒体 | 根拠 (anchor confirmed at frame X) |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | <name> | <n> | <origin> | <caster hand / air / enemy> | <colour> | <blocks> | <tick..tick> | <path> | <3D / sprite / particles> | <frame X, what shows it> |

## 作らないもの
| 物 | 理由 |
|---|---|
| <camera effect, UI marker, stage decor> | <why not a world object> |

## 出来事の順
1. <tick range: what happens, which row>

## エンジン対応
| 台帳 # | primitive | 備考 |
|---|---|---|
| 1 | <projectile / rig / target_line / ...> | <note> |

## 分からないこと
- <what the reference does not show; who decides>
