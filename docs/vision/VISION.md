# Astera VISION (2026-10-26)

Living vision. Why a decision was made: `docs/adr/`. How each area stands today: `docs/vision/areas/`.
What to do next and how far it got: the Board (`.claude/rules/board.md`). Older source: `docs/game-design/vision.md`.

## North Star

> 100% AI-developed Siege Warfare PvP and Metaverse Place on Minecraft Java. Humans set direction, AI implements.

Three pillars: **Siege PvP** (core break, asymmetric large-scale matches) / **UGC** (weapons, maps, skins,
sound) / **Place** (a world players stay in). Only Siege PvP is in scope now.

## Founder's yardstick

> A first-time player understands in 10 minutes: gun, sword, staff, and how Norma wins the match.

Every item is judged against this. "Boot is green" is never done; a bot or the founder must see it work.

## Current scope (core vision, founder 2026-10-22, refined 2026-10-26)

- Game system (queue, match, modes), lightweight weapon system, skill system, a tidy shop / vault / inventory.
- The first-match quality: starter kit, goal visible on the HUD, moments with sound, readable maps.

### Product decisions in force

- **Norma is a standard element of EVERY game mode, not a mode of its own.** Stars pop from map generators,
  a kill bursts the stars the victim carries, each Norma level has a STAR or KILL goal, 100% Orange Juice
  style (ADR-0262 stars match-scoped; `docs/game-design/stars-and-norma.md`). Stars are never a currency,
  never persistent, never a win reward.
- **Matches target siege-scale maps** (castles, towers, gates, height). Keep one small debug arena for e2e.
- **Gathering, crafting and fishing are frozen** (no new work, no deletion decision yet).
- **Sound is sourced free only** (ffmpeg synthesis or CC0). No paid generation services (supersedes the
  fal.ai route of ADR-0026).
- **The starter kit includes a gun; the 2-weapon cap is kept** (`weapon_limits`).
- Inventory: loadout (pre-match kit) / snapshot (per-match save) / personal vault (ender chest). Nothing else.
- Skill damage reaches mobs as well as players. CEL stays unimplemented; use literals.

## Out of scope (do not extend that code)

Phase 4+: UGC marketplace and submission, web admin, gacha, cosmetics shop, guild/trade, Place/Plots
(Phase 5), generative AI for assets (Phase 6, ADR-0010), ops automation and battle pass (Phase 7), CI-based
verification (founder: no CI), GitHub Issues/Projects. Retired docs live in git history (see ADR-0255).

## How we work

- Main session = manager only (`.claude/rules/manager-mode.md`). One session and worktree per track.
- Eight areas, one spec each: ui-access, modes-combat, economy-life, presentation-sound, builds-maps,
  skills-vfx, bots-qa (+ ops-harness, which owns the harness itself).
- Two integration windows per day (12:00 / 21:00 JST), one batched e2e, then push and a batch report.

## Area specs

| Area | File |
|---|---|
| UI / access routes | [areas/ui-access.md](areas/ui-access.md) |
| Modes, combat, weapons | [areas/modes-combat.md](areas/modes-combat.md) |
| Economy and life | [areas/economy-life.md](areas/economy-life.md) |
| Presentation and sound | [areas/presentation-sound.md](areas/presentation-sound.md) |
| Builds and maps | [areas/builds-maps.md](areas/builds-maps.md) |
| Skills and VFX (Limbus) | [areas/skills-vfx.md](areas/skills-vfx.md) |
| Bots and QA | [areas/bots-qa.md](areas/bots-qa.md) |

Phase 4+ ideas are not tracked in a roadmap file any more; park them as `idea` items on the Board.
