package com.astarworks.astera.core.application.port.outbound.animation

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.animation.mining.MiningProgressContext
import com.astarworks.astera.core.domain.animation.player.PlayerAnimationError
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound port: per-player mining-progress HUD (Wave 5animation, ADR-0113).
 *
 * Thin wrapper over Wave 5j [com.astarworks.astera.core.application.port.outbound.IHudGateway] —
 * the wrapper exists so the mining loop can talk to a focused, named
 * port instead of constructing HudComponent trees inline. The default
 * `InMemoryMiningProgressDisplay` impl is the canonical place to keep
 * the HudComponent assembly logic.
 *
 * # Why a separate port, not "just call IHudGateway"
 *
 * 1. **Test isolation**: mining-system tests want to assert "the display
 *    was asked to show progress" without caring about HudComponent
 *    shape. A FakeMiningProgressDisplay records contexts, not Components.
 * 2. **Per-tool styling**: the Wave 5j HudComponent assembly depends on
 *    [com.astarworks.astera.core.domain.world.ToolType] (different glyphs per
 *    tool family). Centralising the lookup in one impl avoids every
 *    mining call site repeating the switch.
 * 3. **Future extension**: Phase 4 will let players opt out of the HUD
 *    via a setting. The display port is the natural place for the
 *    per-player feature toggle.
 *
 * # Lifecycle
 *
 * Callers invoke [update] per tick during an active mining session, and
 * [clear] when the session ends (either successfully or cancelled).
 * The Wave 5animation foundation does not couple this port to the
 * mining lifecycle events — the use case fires events directly.
 */
public interface IMiningProgressDisplay {

    /**
     * Update the HUD for [playerId] with the latest progress snapshot.
     * Calling repeatedly per tick is the canonical usage; impls are
     * expected to dedup HudGateway calls when the underlying layout
     * doesn't change (Phase 1 impl publishes every tick).
     */
    public fun update(
        playerId: PlayerId,
        context: MiningProgressContext,
    ): Result<Unit, PlayerAnimationError>

    /**
     * Clear the mining HUD for [playerId]. Idempotent: clearing an
     * untracked player returns Success.
     */
    public fun clear(playerId: PlayerId): Result<Unit, PlayerAnimationError>
}
