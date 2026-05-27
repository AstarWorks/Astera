package com.astarworks.astera.match.application.port.outbound

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.match.domain.death.PvpDeathRule

/**
 * Outbound port: which [PvpDeathRule] applies to a given match.
 *
 * The resolver consults two sources, in priority order:
 *
 *  1. **Stage YAML override** — `content/stages/<stage>.yaml` may declare
 *     a `pvp_death:` block (Phase 2 stage-author override hook). Wins
 *     when present.
 *  2. **Mode default** — falls back to the per-mode default established
 *     in ADR-0121 §E:
 *
 *     | mode key | default rule |
 *     |---|---|
 *     | `norma` (`null`) | [PvpDeathRule.KeepInventory] |
 *     | `annihilation` | [PvpDeathRule.DropEquipped] |
 *     | `bet-1v1` | [PvpDeathRule.DropAll] |
 *     | any other / unknown | [PvpDeathRule.KeepInventory] |
 *
 * # Why a port (and not just a static lookup table)
 *
 * Stage overrides need to read `IStageRegistry`, and stage YAML schema
 * is in the `application/config/` package — the resolver isn't pure. A
 * port keeps the resolution swappable (in-memory vs Persistence-backed)
 * and lets the `ApplyPvpDeathPolicyListener` stay dumb.
 *
 * # Failure mode
 *
 * Unknown [matchId] returns the default mode rule (KeepInventory). The
 * listener treats any result as authoritative — there is no "rule not
 * found" error. The strictest fallback is the safest one (no items
 * destroyed when in doubt).
 */
public interface IPvpDeathPolicyResolver {

    /** The rule that applies to [matchId] given its mode + stage. */
    public fun resolveForMatch(matchId: MatchId): PvpDeathRule
}
