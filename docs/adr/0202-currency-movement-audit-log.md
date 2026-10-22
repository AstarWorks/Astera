---
status: Accepted
date: 2026-10-22
deciders: founder + Claude (Shop & Economy domain owner)
supersedes: null
superseded_by: null
---

# ADR-0202: Append-only currency movement log

## Status

Accepted

## Context

Wallet balances were the only record of money. Shop purchases, match rewards,
transfers and admin grants published `CurrencyChanged` events that nothing kept, so
"where did my STAR go" and dispute handling had no answer (the `AuditLogListener`
TODO for currency was never done).

## Decision

- New `AuditEvent.CurrencyMovement(player, currency, delta, newBalance, reason)`
  stored in the existing `audit_log` table (`kind = currency_movement`; no migration).
- Economy subscribes to `CurrencyChanged` and writes one row per non-zero movement
  (`AuditCurrencyMovementListener`). Reason strings are stable: `match_reward`,
  `shop:<shop>/<offer>`, `transfer:<uuid>`, `admin_grant`, `betting:<kind>:<uuid>`, ...
- Audit failure never blocks the money movement (logged, swallowed).
- `/astera admin audit` player filter includes the new variant.

## Consequences

- One extra row per currency movement (a match end writes about 2 per player). Acceptable;
  retention is the audit table's policy.
- Movements that bypass `CurrencyChanged` (quest / advancement rewards credit the ledger
  directly) are not yet logged; routing them through the event is a follow-up.
