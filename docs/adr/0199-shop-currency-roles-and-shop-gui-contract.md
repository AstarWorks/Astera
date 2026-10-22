---
status: Accepted
date: 2026-10-22
deciders: founder + Claude (Shop & Economy domain owner)
supersedes: null
superseded_by: null
---

# ADR-0199: Currency roles (STAR / GOLD / GEM) and the shop GUI contract

## Status

Accepted

## Context

Audit of shop and economy found a broken loop. Matches pay mostly STAR
(`match-rewards.yaml`: 100 STAR to a winner, GOLD only 20) but every shop offer
was priced in GOLD (50-160), so the currency players earned could not be spent
and the one they could spend arrived at about 20 per win. The shop GUI showed
bare item icons: no price, no stock, no balance, a purchase on the first
click, and an unhelpful "insufficient balance" message.

## Decision

1. **Roles.** STAR = everyday currency, paid by every match, spent on standard
   weapons (100-320). GOLD = scarce prestige currency from quests,
   advancements and a little per win, spent on the top tier (120-150). GEM =
   premium, never earned in play, reserved for cosmetics / convenience. Starter
   grant is 150 STAR so the cheapest offer is affordable at once.
2. **Shop GUI contract.** Each offer icon is the item itself (its own name,
   lore, stats) plus appended lines: price in the offer's currency (red with
   the missing amount when unaffordable), live stock (from `IShopStockRepository`,
   not the YAML initial), and a click hint. Rows of nine, a footer row with a
   wallet icon listing all currencies. Offers priced >= `ShopIntentHandler.CONFIRM_AT`
   (100) need a confirming second click within 8 s; the open GUI is repainted
   in place after every attempt.
3. `ShopPurchaseError.InsufficientFunds` carries `have` / `need`.

## Consequences

- Tuning is data (`content/shops`, `content/balance`); the code constants are
  only the confirm threshold and window.
- Future shops can reuse the GUI as is (up to 45 offers). Categories / paging
  are not built; split into several shops until needed.
