---
status: Accepted
date: 2026-10-28
deciders: founder
supersedes: null
superseded_by: null
---

# ADR-0294: Remove the GEM currency

## Status

Accepted

## Context

GEM was defined as a premium (paid) currency in ADR-0035 / ADR-0199 but never had a source or a sink: nothing paid it, nothing sold for it, and the wallet tile and the lobby help hid or explained it. It only confused players ("what is GEM, how do I get it?", UX audit 2026-10-27) and kept compliance code (betting ban, non-tradable policy, power-index weight) alive for a currency that does not exist in play. Founder 2026-10-28: 「GEMは一旦削除」.

## Decision

`Currency.GEM` is deleted with every reference: the enum constant, `Attributes.WALLET_GEM`, the `CurrencyPolicies` row, the betting guards, the power-index weight, the profile-card and wallet lines, the help line `hint.currency.gem` and all `gem` i18n keys. Migration `V72__remove_gem_currency.sql` (up-only) removes persisted GEM rows so `Currency.valueOf` never meets one. No shim, no alias. Alongside (ECO-11) a first-time player now starts with 150 GOLD, the price of the recommended first shop offer, so the first purchase works at once.

## Consequences

### Positive
- One persistent currency (GOLD); fewer concepts for a newcomer.
- Less dead compliance code.

### Negative / Trade-offs
- A paid premium currency has to be re-introduced (new ADR) if real-money sales ever return.

### Mitigations
- ADR-0035 stays as history of the compliance posture; `SourceClass.Purchased` is kept for that future.

## Alternatives Considered

- **Hide GEM only**: leaves the enum, DB rows and policy code with no use.

## References

- 関連 ADR: [[adr/0035-marketplace-jp-currency-compliance-cel]], [[adr/0199-shop-currency-roles-and-shop-gui-contract]], [[adr/0271-stars-are-match-scoped-not-a-currency]]
- 関連 docs: [[game-design/economy/README]]
