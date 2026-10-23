package com.astarworks.astera.core.domain.economy

/**
 * Astera in-game currencies.
 *
 * - [GOLD]: account-bound; earned from match completion, dailies, votes
 * - [GEM]: account-bound; premium / paid currency for cosmetics
 *
 * Convertibility rules are owned by Phase 4 (UGC marketplace + economy).
 */
public enum class Currency {
    GOLD,
    GEM,
}
