# Stars and Norma (match-scoped)

Status: spec, ADR-0271. Written 2026-10-24 from the founder's statements and the RTM README
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
- Config: `content/norma-layer/layer.yaml` (`norma_max_level`, `norma_stars_goal`, `norma_kills_goal` lists by level,
  `time_limit_seconds`, `death_drop_ratio`). Norma is a layer of every game mode, not a mode (ADR-0268, MODE-15): a mode overrides any
  key in its `default_config`; `norma_layer: "false"` turns it off (PvE waves).
- **No win by level (ADR-0288):** Norma levels are in-match power progression and never end a match; only the mode's own
  objective or the time limit does. Levels **unlock weapon rarity** (ADR-0292, replacing the
  skill-kind gates of ADR-0288): `rarity_levels` in the layer config (default common 1, uncommon 2, rare 3, epic 4,
  legendary 5). A weapon is usable (attacks and every skill) once the holder's level reaches its item `rarity:` level; below
  it the use is refused with a deny sound and the action bar "Lv N で <rarity> 解放", the weapon stays holdable, its tooltip
  says it is locked until Lv N, each level-up names the rarity it opened and the sidebar shows the next rarity. **Time limit** (`time_limit_seconds`, 600 s of ACTIVE time by
  default, 1200 s in the siege objective modes, `0` = none): the mode's own objective standing first (deliveries, captured
  zones, banked jewels; skirmish and annihilation have none), then the most stars carried (sum over members); tie: the most
  kills; still tied: draw. Kills count for a player's KILL goal and, per team, as the last tie-break (ADR-0272). Winning pays the
  normal reward table (GOLD / XP), never stars.
- **Star supply formula (founder 2026-10-29, anti-snowball; tunable after release):** stars must keep growing as the match goes
  on, so a trailing team always has more to take. Every star generator pop and every Mid wave is scaled by one formula in
  `content/norma-layer/layer.yaml`: `multiplier(t) = min(star_supply_max_multiplier, 1 + star_supply_growth * t / star_supply_ramp_seconds)`
  (`t` = seconds since the match went active), `stars = round(authored amount * multiplier)`. Values: growth 1.5, ramp 600 s,
  max 2.5 (x1.0 at the start, x1.25 at 100 s, x2.5 from 600 s; sudden death still doubles each generator pop on top).
  Code: `StarSupply` (domain), `StarSupplyClock`, applied in `StarDropService.onSpawned`. Each supply is published as
  `StarSupplied`, which is what the pace scenario measures.
- **Annihilation arena generators:** (1) **Mid star wave** (`annihilation-center-wave`): no always-on Mid pop. Every
  `wave_period_seconds` (100) a wave lands, announced `wave_warn_seconds` (10) ahead (title, sound, action bar; moments
  `star-wave-warn` / `star-wave`). Wave size = 30 x multiplier (38 at 100 s, 75 from 600 s), thrown one by one as up to 48 star
  items over a disc of `scatter_radius` (16) around Mid within `scatter_seconds` (8). (2) **Flank generators** (3) stay, smaller:
  2 per 20 s x multiplier. (3) **Team base generators** (`annihilation-base-<team>`): 2 per 20 s x multiplier at each base island;
  only members of that team can pick the stars up (`StarDrop.team`).
- **Pace (balance, content defaults, 2026-10-29):** 5 levels, star goals **10, 30, 60, 100** and kill goals 1,2,3,4. A death bursts
  **half** of the carried stack (`death_drop_ratio` 0.5) as up to 16 items; leaving the match still drops everything.
  Measured by `StarSupplyPaceScenarioTest` (game-sim, real content, real phase timeline build 60 s / engage 600 s / tier 3 300 s;
  the clock starts at match start, so wave 1 lands 40 s into engage). Values below are the supply the formula produces
  (computed from the shipped content with the same rounding; the scenario prints the same table and asserts it).

  | minute | Mid wave + flanks (neutral) | one team base | stage total (neutral + 3 bases) |
  |---|---|---|---|
  | 0-1 | 12 | 4 | 24 |
  | 1-2 | 59 | 7 | 80 |
  | 2-3 | 27 | 9 | 54 |
  | 3-4 | 72 | 9 | 99 |
  | 4-5 | 27 | 9 | 54 |
  | 5-6 | 89 | 12 | 125 |
  | 6-7 | 96 | 12 | 132 |
  | 7-8 | 36 | 12 | 72 |
  | 8-9 | 110 | 14 | 152 |
  | 9-10 | 45 | 15 | 90 |
  | 10-15 (avg) | 90 | 15 | 135 |

  Waves are 100 s apart, so single minutes are lumpy (a wave lands in 6 of 10 minutes); phases are the fair comparison:

  | phase | neutral / min | one team base / min | stage total / min |
  |---|---|---|---|
  | build (0-60 s) | 12.0 | 4.0 | 24.0 |
  | engage (60-660 s) | 68.1 | 11.4 | 102.3 |
  | tier 3 (660-960 s) | 90.0 | 15.0 | 135.0 |

  Best collector of a 4v4-per-team, 3-team lane (4 players per team): ~18% of the neutral supply (about 2x the average 1/12) plus ~40%
  of its own team's base supply. Time to carry 10 / 30 / 60 / **100** stars = **1.7 / 3.3 / 5.0 / 7.3 min**, i.e. level 5 at
  about 7-8 minutes for an active team player; one runner taking only the neutral share would need ~10 min, the average
  collector more (the base stars and the teamwork are what carry a team to level 5). The scenario asserts level 5 lies in 6.5-8.5
  min, that waves land on schedule (100 s, +-2.5 s) and are warned 10 s ahead, that tier 3 supplies > 1.2x engage and engage
  > 1.5x build, and that another team cannot take a base generator's stars. Old values (2026-10-27): 5 stars per 20 s per
  generator, 4 generators, flat all match.
  The e2e image shrinks it (3 levels, star goals 2,3, kill goals 1,2, generators 1 star every 3 s, ratio 1.0, 150 s cap).

### Kill fountain (presentation)
A kill with carried stars throws up to 16 star items from the victim: an evenly spaced, jittered ring of directions, each with its
own outward speed (0.14-0.32 blocks/tick) and upward pop (0.35-0.62), so they arc out and land 2-4.5 blocks away; they cannot be
picked up for 0.9 s so the spray is seen (the victim cannot re-take them for 8 s). Every item is a glowing, named `★ xN` entity.
Layers: END_ROD flash (160), gold dust (WAX_ON, 120) and firework sparks (90) at the death spot, the `star/burst` cue plus the new
free-synth `star/burst-boom` (thump + sparkle cascade, ffmpeg), the killer's `star/burst-gain`. Text on the action bar
(`IActionBarChannel`): victim `-N ★`, killer `+N ★ burst out - grab them!`, each picker `+N ★ (carrying total)`.

### HUD (match sidebar)
Stars carried, own Norma level / max, current goal and progress (`Stars 3/5` or `Kills 1/2`). The lobby shows none of it.

### Rewards that are not stars
Quests, login bonus, level rewards, daily/weekly missions (`content/norma/*.yaml`, the other, mission sense of "norma")
and the match table pay GOLD, XP, items, titles. `Currency.STAR` no longer exists.

### Leaving a running match (MODE-14)
- **Leave = drop:** a connection drop and `/astera travel lobby` (travel menu) are the same. In COUNTDOWN the player is simply
  removed. In ACTIVE: every star they carry bursts at the spot they stand on (all of it, whatever `death_drop_ratio` says; anyone
  alive can pick them up), the seat is held for a **60 s** window (reconnect countdown on the action bar; the lobby routes the
  login back to the game server).
- **Back within 60 s:** same match, same team side, spawn protection, same Norma level and stats (the carried stars are gone).
- **After 60 s (or in SUDDEN_DEATH at once):** forfeit. The player leaves the roster (no win reward, not in the result), a
  loss and a played match are recorded, the pre-match inventory snapshot is restored (online at once, offline on next join).
  The match goes on; if their team is empty the remaining team wins (`TeamVictory`), if nobody is left it is cancelled.
- Tests: `MidMatchLeaveScenarioTest` (game-sim), `MidMatchLeaveE2eTest`, `ReconnectE2eTest`.
