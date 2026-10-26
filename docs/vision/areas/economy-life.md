# Area: economy and life

Owner track `wp/economy-life`. Seed: audit 2026-10-26. Items: Board prefix `ECO-`.

## 構想 (source)
- Core loop (ADR-0271, ADR-0201/0202): match -> GOLD -> shop unlocks weapons -> loadout/vault -> next match. Stars are in-match only, never a currency.
- Inventory: loadout / snapshot / personal vault (ADR-0211). Quests and login bonus pay non-star rewards.
- Scope (founder 2026-10-22): shop + vault + inventory tidy, then stop. Gacha, marketplace, cosmetics, guild/trade are out of scope.
- **Gathering, crafting and fishing are frozen for now** (founder 2026-10-26).
- `docs/game-design/{meta,progression,place,ugc}/README.md` are 1-2 line stubs; there is no progression design.

## 現状
- GOLD only; GEM defined but has no source or sink (hidden from the wallet tile). Match rewards in `content/balance/match-rewards.yaml` (join 10 / win 120 / loss 35 / kill 5 / MVP 35; annihilation win 180; pve win 70).
- Shop `content/shops/example-shop.yaml`: 18 offers priced by rarity (uncommon 150-250, rare 350-400, epic 500-600), `limit: once`, 2-click confirm >=100 G. Starter weapons and dev items are not sold. Every previously unobtainable weapon is now a shop offer or an achievement/level reward.
- Consumables (ECO-04): `heal-potion` (40 G, +8 HP) and `swift-powder` (30 G, 8s speed x1.4) in the shop with `limit: unlimited`; right-click runs `consume_item` (now implemented via `IOwnedItemRemoval`), and `IItemConsumptionRecorder` keeps a match-used copy gone after the match-end snapshot restore.
- Starter grant (`starter-grant.yaml`): pistol + knight-sword (fills the norma weapon cap); a staff is bought.
- Post-match reward block ends with one line naming the cheapest unowned shop unlock and the GOLD gap (or "you can afford"), so the loop match -> GOLD -> shop is spelled out (`NextUnlockFinder`; e2e: playthrough "reward block points at the next shop unlock").
- A player who has bought nothing yet sees the `recommended: true` offer (frost-staff, completing gun/sword/staff) pinned first in the shop with a badge. The wallet tile lists GEM only once a balance exists (GEM has no source).
- Purchase flow pinned from a client by `ShopPurchaseE2eTest` (confirm click, exact charge, delivery, shortfall refusal).
- Level rewards Lv2-30 with round GOLD, titles and cosmetics (`content/progression/level-rewards.yaml`); cosmetics layer (titles, name colours, kill effects) is the second GOLD sink; 14 achievements all pay GOLD.
- Cosmetic earn route (wp/cosmetic-route): the shop footer has a Cosmetics button into the cosmetics menu (previously only the profile card); three cheap starter cosmetics are sold there (mint 60 G, amber 80 G name colours under the 100 G confirm; puff 100 G kill effect); Lv4 grants the kill effect `first-spark`. The post-match next-unlock line points at the priciest affordable unowned cosmetic when the cheapest unowned weapon is out of reach (weapons stay the goal otherwise). Pinned by `CosmeticsScenarioTest` and `ShopPurchaseE2eTest` (shop -> menu -> one-click buy -> Equipped). GEM still has no cosmetic price (GOLD only).
- Daily/weekly missions, 7-day login bonus (monotonic, day7 peak), quests; `/astera daily|missions|quests|achievements`.
- Frozen: PvE drop tables and the `item-collection` quest are parked under `_frozen/`; `content/gathering`, `recipes`, no survival world. Gacha, guild, trade: no code.

## 理想形
One path: a match gives GOLD/XP, the player chooses a weapon or skill to unlock, tries it next match. GOLD has two sinks beyond weapons (cosmetic titles/tags, consumables). Weapons come from three routes: shop (standard), level rewards (mid), achievements/PvE drops (top/persona); prices follow rarity. Reward tables are one coherent sheet (levels to 20-30, achievement rewards, weekly > daily). Frozen life content is parked, not extended.

## 未決
- Starter kit contents (gun added; are `knight-sword`/`frost-staff` still sold in the shop?).
- Do orphan drop tables get item definitions or get removed?
- Fate of GEM (hide until real money exists) and of gathering/recipes (park vs remove).

## Governing ADRs
0034, 0035, 0091, 0101, 0102, 0107, 0124, 0201, 0202, 0210, 0211, 0235, 0271 (stars-are-match-scoped).
