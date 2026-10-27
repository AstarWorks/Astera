---
status: Accepted
date: 2026-10-24
deciders: founder + Claude
supersedes: null
superseded_by: null
---

# ADR-0271: Stars are match-scoped, not a currency

## Status

Accepted. Level-as-win parts are superseded by ADR-0288. Supersedes the STAR parts of ADR-0199 (currency roles), ADR-0177 (lobby/match sidebar STAR wallet),
ADR-0235 (level-reward STAR) and ADR-0237 (STAR sources list). Those ADRs stay immutable; this one wins on conflict.

## Context

Founder: "Stars are not something you get for winning a match. They are an item used inside the match and are never
carried out of it. Right now there is a mysterious number with a default value."

What existed: `Currency.STAR`, a persistent wallet entry (`wallet.star` in `player_attributes`), a 150 STAR starter
grant, a STAR column in `match-rewards.yaml`, STAR level / login / quest / norma rewards, shop prices in STAR, a
`Stars: N` line on the lobby and match sidebars and a `+N STAR` toast. Meanwhile the real RTM concept (glossary:
"Star = in-match resource spawned periodically by Generators on the map"; RTM README: level-up needs stars or kills,
stars are not consumed) was dead: `ResourceSpawned` from the Star Generators had no subscriber, so generators fired
into the void.

## Decision

1. **No persistent star.** `Currency.STAR` is deleted. Persistent currencies are GOLD (everyday + scarce, all former STAR
   amounts re-denominated 1:1 into GOLD; match rewards pay `star + gold` as GOLD) and GEM (premium). A Flyway migration
   removes stored `wallet.star`. No starter star, no match-reward star, no lobby star line.
2. **Stars are match-scoped.** A per-(match, player) counter owned by the match plugin (in memory, on the server that
   hosts the match). Everyone starts a match with 0. It is never written to the wallet, ledger, Postgres, inventory or
   handoff snapshot, and is dropped on `MatchEnded`.
3. **Obtain:** Star Generators (`content/generators/`) fire on their period; the stars land in a capped stockpile at the
   generator and a match participant standing within the pickup radius collects them (on movement, and at spawn time if
   already standing there). Winning, kills and training-dummy hits give no stars.
4. **Show:** the in-match sidebar shows the viewer's current match stars. The lobby shows none.
5. **Spend:** RTM keeps stars (level-up threshold "stars or kills, not consumed"). The store exposes a `spend` operation
   returning `Result`; any larger in-match sink (upgrade shop, match level) is a later decision.

## Consequences

- Shop weapons are priced in GOLD; balance of GOLD vs old STAR is a tuning pass, not done here.
- Players' old STAR balances are discarded (pre-release, no external users).
- The e2e suite asserts: 0 stars at match start, stars rise only by standing on a generator, no star line or star balance
  in the lobby, no star in reward chat after a win.
