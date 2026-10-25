# Area: economy and life

Owner track `wp/economy-life`. Seed: audit 2026-10-26. Items: Board prefix `ECO-`.

## 構想 (source)
- Core loop (ADR-0271, ADR-0201/0202): match -> GOLD -> shop unlocks weapons -> loadout/vault -> next match. Stars are in-match only, never a currency.
- Inventory: loadout / snapshot / personal vault (ADR-0211). Quests and login bonus pay non-star rewards.
- Scope (founder 2026-10-22): shop + vault + inventory tidy, then stop. Gacha, marketplace, cosmetics, guild/trade are out of scope.
- **Gathering, crafting and fishing are frozen for now** (founder 2026-10-26).
- `docs/game-design/{meta,progression,place,ugc}/README.md` are 1-2 line stubs; there is no progression design.

## 現状
- GOLD only; GEM defined but has no source or sink. Match rewards in `content/balance/match-rewards.yaml` (join 10 / win 120 / loss 35 / kill 5 / MVP 35; annihilation win 180; pve win 70).
- Shop `content/shops/example-shop.yaml`: 16 weapons 100-320 G, `limit: once`, 2-click confirm >=100 G. It also sells the starter weapons.
- Starter grant (`starter-grant.yaml`): knight-sword + frost-staff, no gun.
- XP curve `100*(n-1)*n/2`; level rewards only Lv2/3/5/10 (`content/progression/level-rewards.yaml`), odd amounts (102, 205); season ends 2027-01-01.
- Daily/weekly missions (6), 7-day login bonus (day7 25 < day6 65), 14 achievements (no rewards, all `knight-sword` icon), 3 quests; `/astera daily|missions|quests|achievements`.
- Not obtainable: crimson-chain-axe, edict-blade, orrery-staff, sigil-rifle, rtm-blaster (no shop/reward path).
- PvE drop tables reference undefined items (`dust-of-ember`, `imp-claw`, `wolf-tooth`); quest `item-collection` unreachable.
- Frozen/orphan: `content/gathering`, `recipes`, no survival world. Cosmetics: `CosmeticSummary.EMPTY`; titles granted but not equippable. Gacha, guild, trade: no code.

## 理想形
One path: a match gives GOLD/XP, the player chooses a weapon or skill to unlock, tries it next match. GOLD has two sinks beyond weapons (cosmetic titles/tags, consumables). Weapons come from three routes: shop (standard), level rewards (mid), achievements/PvE drops (top/persona); prices follow rarity. Reward tables are one coherent sheet (levels to 20-30, achievement rewards, weekly > daily). Frozen life content is parked, not extended.

## 未決
- Starter kit contents (gun added; are `knight-sword`/`frost-staff` still sold in the shop?).
- Do orphan drop tables get item definitions or get removed?
- Fate of GEM (hide until real money exists) and of gathering/recipes (park vs remove).

## Governing ADRs
0034, 0035, 0091, 0101, 0102, 0107, 0124, 0201, 0202, 0210, 0211, 0235, 0262 (stars-are-match-scoped).
