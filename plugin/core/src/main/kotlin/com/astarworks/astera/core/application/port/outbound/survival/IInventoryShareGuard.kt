package com.astarworks.astera.core.application.port.outbound.survival

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.survival.GameModeContext

/**
 * Outbound port: cross-mode inventory carry rules (Wave 5pve-survival /
 * ADR-0107).
 *
 * Enforces the user-confirmed policy:
 *
 *  > 生活で集めた wood/ore を PvP match に持ち込めないよう保証 (経済 balance
 *  > 保護)。currency は OK。
 *
 * Implementations decide per-item whether it may cross the
 * `(from, to)` mode boundary. The default Core implementation
 * (`InMemoryInventoryShareGuard`) blocks every gathering-tier item from
 * entering a PvP context and allows everything else; admins can override
 * per-item rules at runtime.
 *
 * # Why a port (not a hardcoded rule table)
 *
 * The Wave 5f marketplace + Wave 5pve loot grid both want to consult
 * "is this item carriable across modes?" without re-implementing the
 * matrix. The port lets the persistence plugin overlay a database-loaded
 * rule set (Phase 4) while keeping the Phase 1 default fully in-Core.
 */
public interface IInventoryShareGuard {

    /**
     * `true` if [itemId] may be carried from [from] mode context to [to].
     * The same-mode case (`from == to`) is always `true` — sanity-checked
     * by every implementation.
     */
    public fun canCarryAcrossMode(itemId: ItemId, from: GameModeContext, to: GameModeContext): Boolean

    /**
     * Add a deny rule: [itemId] cannot leave [from] for [to]. Idempotent.
     */
    public fun deny(itemId: ItemId, from: GameModeContext, to: GameModeContext)

    /** Remove a previously-registered deny rule. Returns `true` if anything was removed. */
    public fun clearDeny(itemId: ItemId, from: GameModeContext, to: GameModeContext): Boolean
}
