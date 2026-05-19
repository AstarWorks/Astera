package com.astarworks.astera.match.domain.player

import com.astarworks.astera.core.domain.attribute.AttributeKey

/**
 * Feature-local [AttributeKey] registry for **player-scoped** match
 * attributes (ADR-0058 §2, Wave 5g).
 *
 * Separate from [com.astarworks.astera.match.domain.generator.GeneratorAttributes]
 * (generator-scoped) and [com.astarworks.astera.match.domain.anomaly.AnomalyAttributes]
 * (anomaly-side metadata) because the *subject* is the player container:
 * modifiers added here are looked up via [IPlayerAttributeContainerStore]
 * → `containerFor(playerId).effectiveNumber(<key>)`.
 *
 * ## Keys
 *
 * - [RESPAWN_RATE_MULTIPLIER] — multiplicative on the player respawn cadence.
 *   Default (no modifiers) resolves to `1.0`. SpawnSurge anomaly sets this to
 *   `> 1.0` on every active player in the match for faster respawn cadence;
 *   Wave 6's `RespawnUseCase` reads it via
 *   `IAttributeContainer.effectiveNumber(RESPAWN_RATE_MULTIPLIER)` with a
 *   sensible default fallback.
 */
public object PlayerMatchAttributes {
    public val RESPAWN_RATE_MULTIPLIER: AttributeKey =
        AttributeKey("match.respawn_rate_multiplier")
}
