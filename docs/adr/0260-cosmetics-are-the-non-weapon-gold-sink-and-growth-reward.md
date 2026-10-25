---
status: Accepted
date: 2026-10-26
deciders: founder, economy-life track
supersedes: null
superseded_by: null
---

# ADR-0260: Cosmetics are the non-weapon GOLD sink and growth reward

## Status

Accepted

## Context

Growth was weapons bought with GOLD only; after all 16 shop weapons GOLD had no use, and level rewards and
achievements granted titles that could not be equipped or seen (`CosmeticSummary.EMPTY`). Stars are
match-scoped and never a currency (ADR-0251); gacha, guild and trade are out of scope.

## Decision

Add a minimal, strictly visual cosmetic layer. `content/cosmetics/<id>.yaml` defines titles, name colours and
kill effects; a cosmetic with `price_gold` is sold in the cosmetics menu, one without is granted by level
rewards (`titles:` / `cosmetics:`) or achievements (`reward_title:`). Ownership reuses the permanent
`player_unlocks` entitlements (`title:<id>` / `cosmetic:<id>`); the equipped choice per kind is stored in
`player_cosmetic_loadout` (V70). The menu (`cosmetic.open`, from the profile card) buys with a two-click
confirm and equips. Equipped title and name colour show in chat and the tab list (a match keeps the team
colour), the kill effect plays at the victim on a PvP kill, and the profile card summaries are computed
from this state. No cosmetic changes any stat. Level rewards were also raised on Lv3-5 so a first-day player
can afford a flagship weapon.

## Consequences

### Positive
- GOLD keeps a sink after the weapons are bought; level and achievement rewards become visible.

### Negative / Trade-offs
- The menu is reachable only from the profile card until the main menu gets a tile.
- Name colour and kill-effect values are vanilla colours / data-less particles only.
