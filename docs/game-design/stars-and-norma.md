# Stars and Norma (match-scoped)

Status: spec, ADR-0251. Written 2026-10-24 from the founder's statements and the RTM README
(https://github.com/Y-RyuZU/RyuZUTechnicalMagic, README.md, fetched 2026-10-24; Japanese quotes below are from it).

## Founder statements (authoritative)

- Stars: "普通にマップにポップするものを取る" - things that pop up on the map and are picked up.
- "キルでその人の持っているスターが弾ける" - when a player is killed, the stars they carry burst out.
- Norma: "100%オレンジジュースのノルマ" - norma as in 100% Orange Juice.
- "クエストやログインボーナスでもらえるのはスターではなく、それ以外の報酬" - quests and login bonus never pay stars.
- Stars are never persistent: 0 at match start, gone at match end, not a win reward, no wallet.

## Sources

| Source | What it says |
|---|---|
| RTM README, "スタージェネレーター" | Generators on the map make stars pop periodically (BedWars-like). One big generator in the centre, four medium ones around, one small one at each base. Stars are used for item use, ultimate use and level-up. Generators output more stars as the game goes late. On death "一定割合" (a fixed ratio) of the victim's stars drops on the spot, scattered somewhat. |
| RTM README, "レベルシステム" | Level-up needs a set number of stars OR kills; stars are NOT consumed by it. Level-up can be done at your own base. |
| RTM README, "特殊アイテム" | One-shot magic items pop at random during a match; using one costs stars (some have a level limit). |
| RTM README, CoreBreak flow | Collect stars, spend stars to beat enemies, run the norma check, level up. |
| `docs/architecture/rtm-divergence.md` (Star generator, HUD "CT / Star count / Level count", `LevelUpMission` per-level KILL vs STAR, `ItemGenerator`) | Astera mirrors: generators, HUD with star count and level, a per-level KILL-or-STAR requirement. |
| `docs/glossary.md` | Star = in-match resource spawned periodically by Generators; Norma = mission/quota for levelling. |
| 100% Orange Juice (public rules) | Norma level N asks the player for one of two goals, chosen when they level: collect enough Stars, or enough Wins (KOs). Reaching the goal at a check raises the Norma level; the highest Norma levels are the way to win the game. |

The RTM README gives no numeric death-drop ratio. Decision: the ratio is content config
(`death_drop_ratio`), shipped as **1.0** (all carried stars burst), matching the founder's wording.

## Spec

### Stars
- Per-(match, player) integer counter held by the match plugin on the host server. 0 at match start; dropped at match end.
  Never in the wallet, Postgres, inventory snapshot or lobby HUD.
- **Sources:** (1) Star Generators (`content/generators/`) pop stars on their period; stars lie on the map as pickable
  star drops: a glowing, named `★ xN` NETHER_STAR item entity. Collecting is the ordinary item pickup (vanilla pickup
  range and delay, pickup animation); there is no proximity crediting. The picked item is removed and counted: the
  carried count lives only in the match-scoped store, so no item ever enters an inventory (nothing to drift, drop with
  Q or move into an ender chest, and nothing to clear on leave); "+N ★" action bar and a pickup sound). Output grows late in the match
  (generator `late_game_multiplier`). (2) Kill burst (below). Nothing else: winning, quests, login, level rewards, dummy
  hits and lobby activity give no stars.
- **Kill burst:** when a player dies in a match, `floor(carried * death_drop_ratio)` stars leave the victim's counter and
  become pickable drops scattered around the death position (stacks of 1, random offsets within ~2.5 blocks, capped
  per death). The killer is not credited directly; anyone alive (including the killer and the victim's team) can pick
  them up. The victim keeps `carried - dropped`.
- **Use:** stars are not consumed by norma level-up (RTM). Spending (items, ultimates) is a later feature; the store
  keeps a `spend` operation. The respawn-time sink invented earlier is removed (no document supports it).

### Norma levels (100% Orange Juice style)
- Every player has a Norma level, starting at 1, up to `max_level`.
- At each level the player holds one active goal, picked from two: **STAR** (carry at least `stars_goal(level)` stars) or
  **KILL** (get `kills_goal(level)` kills since reaching this level). Default pick if the player never chooses:
  STAR. Choose in the picker GUI that opens at match start and at each level-up (closes after 10 s; no pick = STAR) or with
  `/astera norma choose star|kill`. The choice is allowed any time before
  the goal is reached.
- Reaching the goal raises the Norma level at once (no home check in v1), resets the kill progress, re-opens the choice.
  Stars are kept.
- **Presentation:** a level-up shows a title ("Norma cleared!" + Lv N/max), plays a level-up sound, bursts particles and
  a firework at the player and tells the whole match in chat; the top level gets a bigger version. When the picker
  opens: a title and chime; the tiles show the goal (need N ★ / N kills) and the current progress; a pick gives a
  confirmation sound and action bar; the 10 s default is announced on the action bar.
- Config: `content/game-modes/norma.yaml` (`norma_max_level`, `norma_stars_goal`, `norma_kills_goal` lists by level).
- **Win:** the first team to have a member reach `max_level` wins the match. The existing per-team kill score
  (`score_threshold`) and the time limit still end the match too (time limit: more Norma levels, then more kills, wins).
  Winning pays the normal reward table (GOLD / XP), never stars.

### HUD (match sidebar)
Stars carried, own Norma level / max, current goal and progress (`Stars 3/5` or `Kills 1/2`). The lobby shows none of it.

### Rewards that are not stars
Quests, login bonus, level rewards, daily/weekly missions (`content/norma/*.yaml`, the other, mission sense of "norma")
and the match table pay GOLD, XP, items, titles. `Currency.STAR` no longer exists.
