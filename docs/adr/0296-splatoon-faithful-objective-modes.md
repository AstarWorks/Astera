# ADR-0296: Splatoon-faithful objective modes on one count model

- Status: Accepted
- Date: 2026-10-28
- Items: MODE-17

## Context

Founder 2026-10-28: the objective modes should be the Splatoon rules in Minecraft form (wool = Splat Zones, TNT = Rainmaker, Mini TNT = Clam Blitz), steal-jewelry stays an original mode, and a match should take 8 to 12 minutes with a 15 minute cap. Round 1 gave every mode a shared count (`SiegeGameMode`: `countEvent` / `countDown` / `holdsObjective`); the per-mode sources (3 deliveries, majority of zones, planting minis) did not match the source rules.

## Decision

The count model stays: every team has a count, a knockout ends the match at 0, overtime holds the match while `holdsObjective`. Per-mode sources change:

- **capture-wool (Splat Zones)**: zones are the stage's `wool-*` regions. A zone needs `capture_seconds` of uncontested presence. While one team owns EVERY zone the other team's count drops each second; contested zones do not progress. Retaking a zone costs the new owner a penalty (`retake_penalty_pct`, capped by `penalty_cap_seconds`) before its count moves.
- **carry-tnt (Rainmaker)**: one TNT at `tnt-spawn`, shielded until the shield HP is broken. The count follows the carrier's progress along the route (`checkpoint-<team>-<n>` then `deliver-<team>`); reaching the enemy goal is a knockout. The carrier is silenced (no weapons) and re-shields at checkpoints (timed immunity). A dead carrier drops the TNT, which returns to the centre after `dropReturnTicks`. The weak TNT throw is not implemented.
- **carry-mini-tnt (Clam Blitz)**: clams regrow on `clam-<n>` spots; carry limit 8. Ten carried clams turn into a power TNT for the collector. A power holder inside an enemy `basket-<team>` region channels a throw: the basket count takes `count_per_power` and its barrier opens for `barrier_open_seconds`; only while open can normal minis be planted there (`count_per_mini` each). A dead carrier scatters clams into piles. Overtime holds while any team member carries a power TNT.
- **steal-jewelry (original)**: 6 jewels per team, steal channel 4 s (damage interrupts it), five banked jewels are a knockout, carriers are slowed per carried item.

`SiegeGameMode` implements the new `IObjectiveStateQuery.objectiveSnapshot(matchId)` returning a sealed `ObjectiveSnapshot` (`Zones`, `Rainmaker`, `Clams`, `Jewels`) so bots and UI read mode state without parsing chat or sidebar text. `SiegeEnv` gained a `statuses` supplier (silence / immunity).

Stage contract (owned by the map track): `wool-<x>`; `tnt-spawn`, `deliver-<team>`, `checkpoint-<team>-<n>`; `clam-<n>`, `basket-<team>`; `jewelry-<team>`, `base-<team>`. A mode whose regions are missing refuses to start via `missingRegions`.

## Consequences

### Positive
- Each mode has the source mechanic of its Splatoon counterpart; match length is tuned through the yaml counts (the e2e image shortens them in `docker/e2e-tune-content.sh`).
- A state query gives bots and HUD one typed source.

### Negative / Trade-offs
- The silence status spec is built in match code; the Paper status container must accept a spec without a `content/status` entry (runtime verification needed).
- No Rainmaker weak throw; no carry limit for steal-jewelry.
- Route progress is measured against the checkpoint polyline, so stages need sensibly ordered checkpoints.
