---
status: Accepted
date: 2026-11-01
deciders: founder (via astera-5c), manager
supersedes: null
superseded_by: null
---

# ADR-0318: Star economy v2: player-count budget, action sources, multi-spot timers, kill share

## Status

Accepted. Supersedes the 2026-10-29 "anti-snowball = option A only" supply decision recorded in `docs/game-design/stars-and-norma.md` (that
section is rewritten in the same change; ADR bodies are untouched). Stars stay match-scoped ([[adr/0271-stars-are-match-scoped]]).

## Context

Founder 2026-11-01: 「stars pop at fixed spots, one at a time; total amount must not be fixed regardless of player count; maybe issue stars from each
player's actions (exploring, breaking blocks); but then there is little incentive to kill enemies for stars, improve that too; control the overall amount
by a table or formula.」 The old supply was a time ramp only (`StarSupply`), the same at 3 and 12 players, with timer generators that popped at one fixed
point and a Mid wave of a fixed 30 stars, and a kill gave the killer nothing directly.

## Decision

1. **Budget formula in content** (`content/norma-layer/layer.yaml`, `star_supply_*`): `budget(t, N) = per_player_minute x ramp(t) x N_alive` stars per
   minute for the whole match. Every source draws from this budget through a token ledger (`StarBudget`): a grant must fit the source's own bucket
   (`star_share_<source>` of the budget; per player per minute for mining, blocks and caches) and the match-wide bucket. Buckets start empty at match
   start, so the issued total cannot exceed the budget integral. Refused/cut requests are counted as cap hits.
2. **The fixed single spot is replaced.** Flank and base timer generators carry a list of candidate `spots`; every pop picks the next spot by a seeded
   shuffled rotation (deterministic in the sim, never the same spot twice in a row), at most 1 star per spot. They are a minor source: spot share
   0.12, hard validator maximum 0.15. Each timer generator is its own lane of the spot bucket so one cannot starve the others.
3. **Action sources:** mining a Mid ore node (`PaperOreNodeListener` -> `StarActionService`), breaking a block of `star_blocks` (`PaperStarBlockListener`),
   and exploration caches (`content/generators/annihilation-cache.yaml`: fixed one-shot spots, `respawn_seconds` 90, opened by walking within
   `open_radius`). The star goes straight to the player, no ground item.
4. **Mid wave sized by N:** `size = amount(7, per player) x N_alive x ramp(t)`, drawn from the wave bucket.
5. **Kill incentive:** the victim loses ALL carried stars. The killer receives `star_kill_share` (40%) directly, `star_kill_ground_share` (20%) bursts on
   the ground, the rest is lost. Plus a bounty = `clamp(round(0.5 x (victim carried - roster average)), 0, 12)` from the bounty bucket, announced by an
   action-bar hint (`astera.match.star.kill-income` / `astera.match.star.bounty`). A kill on the leader therefore pays far more than one on a trailing player.
6. **Coordinates** come from anni-map's Highmarch (ADR-0317): 4 candidates per base, 3 per flank outpost, 9 caches + the Mid chapel chest, wave at the quarry
   floor y=51, max scatter 17. `ShippedArenaStagesBuildTest` now checks every candidate spot has solid ground below and free space at its own height.

### Numbers (my call, balance)

Base 16 stars per player-minute, ramp (minutes:multiplier) `0:0.25, 1:0.75, 3:1.0, 11:1.4, 16:1.6`; shares spot .12, mining .22, blocks .15, cache .15,
wave .30, bounty .04 (sum .98); mining 1 per node, 1 per listed block, caches 4 x ramp. Reason: with the scripted player behaviour below, income is
about 16.5 stars per player-minute at 3, 6 and 12 players (82-87% of the formula), the 100-star level lands at about 7.8-8.1 minutes (the old pace was
about 7-8 minutes at 6 players), and levels 1-3 arrive at about 1.7 / 3.4 / 5.2 minutes. The kill share is 40% so a kill is worth roughly a 1-2 minute detour
for a carrier of 40+ stars, while the 100% loss on death keeps carrying risky.

### Simulation (`StarEconomyScenarioTest`, shipped content, 15 game minutes)

Scripted behaviour per player: 4 ore nodes/min, 6 listed blocks/min, a cache walk every 20 s, pickup of everything lying around, 4 scripted kills on the
richest player.

```
ADR-0318 star economy, 15 game minutes, shipped content

== N=3: budget 284.5 stars/player over 15 min (18.97 /player-min), issued 16.5 /player-min ==
source   | per player-min | share of issued | requested | cap hits
spot     |   2.27 |  13.7% |    450 |  348
mining   |   3.62 |  21.9% |    180 |   17
blocks   |   2.80 |  16.9% |    270 |  144
cache    |   2.71 |  16.4% |    327 |   86
wave     |   5.13 |  31.0% |    236 |    1
bounty   |   0.02 |   0.1% |      1 |    0
total    |  16.56 | 100.0%
per minute (stars, whole match): m0=8 m1=36 m2=28 m3=51 m4=57 m5=33 m6=58 m7=38 m8=57 m9=77 m10=31 m11=75 m12=44 m13=66 m14=86
wave sizes: [13, 21, 23, 25, 27, 28, 30, 31, 33] (per player at first wave 4.3)
kills: direct 174, ground 86, lost 177, bounty payouts 1 (1 stars); kill income share of all income 19.0%
leader kill payout 40 vs trailing kill payout 2
minutes to level (carry 10/30/60/100): 1.6 3.4 5.0 7.8
timer generator (spot) share: 13.7% of issued, 12.0% of the budget
spot distribution (pops per candidate spot):
  annihilation-base-blue     4 4 5 4
  annihilation-base-yellow   4 4 4 5
  annihilation-flank-west    6 6 5
  annihilation-flank-north   6 6 5
  annihilation-base-red      4 5 4 4
  annihilation-flank-east    6 5 6

== N=6: budget 284.5 stars/player over 15 min (18.97 /player-min), issued 16.5 /player-min ==
source   | per player-min | share of issued | requested | cap hits
spot     |   2.27 |  13.6% |    450 |  246
mining   |   3.61 |  21.7% |    360 |   35
blocks   |   2.80 |  16.9% |    540 |  288
cache    |   2.62 |  15.8% |    531 |  128
wave     |   5.16 |  31.0% |    473 |    1
bounty   |   0.16 |   0.9% |     14 |    0
total    |  16.61 | 100.0%
per minute (stars, whole match): m0=15 m1=68 m2=64 m3=95 m4=113 m5=68 m6=112 m7=88 m8=112 m9=149 m10=80 m11=139 m12=91 m13=133 m14=168
wave sizes: [26, 43, 46, 50, 53, 57, 60, 63, 66] (per player at first wave 4.3)
kills: direct 225, ground 112, lost 228, bounty payouts 4 (14 stars); kill income share of all income 13.9%
leader kill payout 40 vs trailing kill payout 2
minutes to level (carry 10/30/60/100): 1.7 3.3 5.1 7.8
timer generator (spot) share: 13.6% of issued, 12.0% of the budget
spot distribution (pops per candidate spot):
  annihilation-base-blue     8 9 9 8
  annihilation-base-yellow   8 9 9 8
  annihilation-flank-west    12 11 11
  annihilation-flank-north   11 12 11
  annihilation-base-red      9 8 9 8
  annihilation-flank-east    11 11 12

== N=12: budget 284.5 stars/player over 15 min (18.97 /player-min), issued 15.5 /player-min ==
source   | per player-min | share of issued | requested | cap hits
spot     |   2.17 |  13.9% |    450 |   60
mining   |   3.62 |  23.1% |    720 |   69
blocks   |   2.80 |  17.9% |   1080 |  576
cache    |   1.75 |  11.2% |    623 |  147
wave     |   5.13 |  32.8% |    941 |    1
bounty   |   0.17 |   1.1% |     31 |    0
total    |  15.63 | 100.0%
per minute (stars, whole match): m0=32 m1=134 m2=112 m3=193 m4=218 m5=126 m6=224 m7=154 m8=239 m9=242 m10=160 m11=257 m12=151 m13=302 m14=270
wave sizes: [52, 85, 92, 99, 106, 113, 120, 125, 131] (per player at first wave 4.3)
kills: direct 237, ground 117, lost 243, bounty payouts 4 (31 stars); kill income share of all income 8.8%
leader kill payout 40 vs trailing kill payout 2
minutes to level (carry 10/30/60/100): 1.7 3.4 5.3 8.1
timer generator (spot) share: 13.9% of issued, 11.4% of the budget
spot distribution (pops per candidate spot):
  annihilation-base-blue     16 16 16 17
  annihilation-base-yellow   16 16 17 16
  annihilation-flank-west    22 21 22
  annihilation-flank-north   22 22 21
  annihilation-base-red      16 17 16 16
  annihilation-flank-east    21 22 22


Gradle Test Executor 15 finished executing tests.

> Task :tools:game-sim:test
Generating HTML test report...
```

## Consequences

### Positive
- Supply follows the player count: per-player income is about equal at 3, 6 and 12 players.
- Exploring and mining pay; killing a leader pays most; timer spots rotate instead of one fixed pop.

### Negative / Trade-offs
- The victim loses 100% of carried stars (was 50%), which interacts with norma carry goals (a 100-star goal is harder to hold).
- Norma level costs (not changed here) change what the same supply means; the supply must be retuned if they move.
- Bots that waited at one generator position (e2e) no longer find a single spot.

### Mitigations
- Every number is content (`layer.yaml`, generator yaml); the scenario prints the table so a retune is one edit and one run.

## Alternatives Considered

- **Keep the time ramp and only add per-N multipliers**: still one fixed spot and no incentive to act or kill.
- **Spot generators as the main source with more spots**: the founder's complaint is exactly that stars come from timers at fixed places.

## References

- [[adr/0271-stars-are-match-scoped]], [[adr/0317-original-annihilation-map-highmarch]]
- `docs/game-design/stars-and-norma.md`, `StarEconomyScenarioTest`
