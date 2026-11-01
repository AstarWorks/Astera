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
(superseded by ADR-0318: the victim loses all carried stars, split into killer share / ground / lost).

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
- **Kill rule (ADR-0318; the killer is credited, see Pace):** when a player dies in a match, all carried stars leave the victim's counter and
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
- Reaching the goal only marks the player READY (action bar + sidebar tell them to go home). The level rises when the player runs the
  Norma check (`/astera norma check`) inside their OWN team's base zone while still meeting the goal (a player who died and lost
  stars is no longer ready). The zone is a disc of `norma_check_radius` (default 16, `content/norma-layer/layer.yaml`) around the
  centroid of the team's TEAM spawn points. Enemy base, outside any base, or an unmet goal: refused with a message (shortfall shown).
  A successful check resets the kill progress and re-opens the choice. Stars are still NOT consumed by the check (founder to decide).
  Proven by game-sim `NormaCheckScenarioTest`; bots walk home and check (`BotBrain.normaHomeDest`). A right-click/hotbar trigger is not built yet.
  Stars are kept.
- **Presentation:** a level-up shows a title ("Norma cleared!" + Lv N/max), plays a level-up sound, bursts particles and
  a firework at the player and tells the whole match in chat; the top level gets a bigger version. When the picker
  opens: a title and chime; the tiles show the goal (need N ★ / N kills) and the current progress; a pick gives a
  confirmation sound and action bar; the 10 s default is announced on the action bar.
- Config: `content/norma-layer/layer.yaml` (`norma_max_level`, `norma_stars_goal`, `norma_kills_goal` lists by level,
  `time_limit_seconds`). Norma is a layer of every game mode, not a mode (ADR-0268, MODE-15): a mode overrides any
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
- **Star supply (ADR-0318, supersedes the 2026-10-29 time-ramp-only formula):** `budget(t, N) = star_supply_per_player_minute x ramp(t) x N_alive`
  stars per minute (`content/norma-layer/layer.yaml` `star_supply_*`, `star_share_*`). Every source (timer spots, ore mining, listed blocks, exploration
  caches, Mid waves, bounty) draws from it through `StarBudget` with its own cap. Code: `StarEconomy`, `StarSource` (domain), `StarBudget`, `StarIssuer`,
  `StarActionService`, `StarCacheService`. Each supply is published as `StarSupplied`.
- **Annihilation arena generators (ADR-0318):** (1) **Mid star wave** (`annihilation-center-wave`): every `wave_period_seconds` (100) a wave lands,
  announced 10 s ahead; size = 7 stars per player x players alive x ramp(t), thrown over a disc of `scatter_radius` around Mid within 8 s.
  (2) **Flank and base timer generators** are a minor source: each has several candidate `spots` (rotation, never the same spot twice in a row, one star per
  spot); base-generator stars are team-locked (`StarDrop.team`). (3) **Caches** (`annihilation-cache`): one-shot spots with a 90 s respawn.
  (4) **Actions:** mining and listed blocks pay the player directly.
- **Pace (ADR-0318):** 5 levels, star goals 10, 30, 60, 100, kill goals 1,2,3,4. The victim of a kill loses all carried stars: the killer gets
  40% directly, 20% bursts on the ground, the rest is lost, plus a capped bounty on carriers above the roster average. Leaving a running match still drops
  everything. Measured by `StarEconomyScenarioTest` (about 16.5 stars per player-minute at 3, 6 and 12 players; 100 stars at about 8 minutes).
- **Leave = drop:** a connection drop and `/astera travel lobby` (travel menu) are the same. In COUNTDOWN the player is simply
  removed. In ACTIVE: every star they carry bursts at the spot they stand on (all of it, regardless of the kill rule; anyone
  alive can pick them up), the seat is held for a **60 s** window (reconnect countdown on the action bar; the lobby routes the
  login back to the game server).
- **Back within 60 s:** same match, same team side, spawn protection, same Norma level and stats (the carried stars are gone).
- **After 60 s (or in SUDDEN_DEATH at once):** forfeit. The player leaves the roster (no win reward, not in the result), a
  loss and a played match are recorded, the pre-match inventory snapshot is restored (online at once, offline on next join).
  The match goes on; if their team is empty the remaining team wins (`TeamVictory`), if nobody is left it is cancelled.
- Tests: `MidMatchLeaveScenarioTest` (game-sim), `MidMatchLeaveE2eTest`, `ReconnectE2eTest`.
