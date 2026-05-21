package com.astarworks.astera.combat.application.port.outbound.aoe

import com.astarworks.astera.combat.domain.aoe.ExplosionError
import com.astarworks.astera.combat.domain.aoe.ExplosionResult
import com.astarworks.astera.combat.domain.aoe.ExplosionSpec
import com.astarworks.astera.core.domain.Result

/**
 * Outbound port: trigger a spherical AOE explosion in the world (Wave 5m,
 * ADR-0088).
 *
 * Why a dedicated port (vs. routing every explosion through `DamageRule`
 * + `INearbyTargetQuery`):
 *  1. Block destruction is a vendor concern — the application layer
 *     should not have to know about block hardness. The gateway picks
 *     the right algorithm per [com.astarworks.astera.combat.domain.aoe.BlockDamagePolicy].
 *  2. Vanilla `World.createExplosion(...)` produces both the block list
 *     and the entity-hit list in a single tick-aligned call. Splitting
 *     entity query + damage apply across application code would either
 *     duplicate the work (worse perf) or desync from the block side
 *     (block break event fires before damage, breaks assist attribution).
 *  3. Wave 6a's Folia migration may swap a region-scheduled
 *     `FoliaExplosionGateway` impl. Keeping the surface narrow means
 *     that swap is a single registration change.
 *
 * # Lifecycle
 *
 * Successful [trigger] publishes
 * [com.astarworks.astera.combat.domain.event.aoe.ExplosionTriggered] on
 * the shared domain event bus so downstream listeners (analytics, status
 * effect chains, sound) can react without polling.
 *
 * # Threading
 *
 * Called only from the Paper main thread. The Wave 6a impl uses
 * `World.createExplosion` synchronously; the InMemory impl is purely
 * single-threaded.
 */
public interface IExplosionGateway {

    /**
     * Trigger an explosion described by [spec].
     *
     * Returns [com.astarworks.astera.combat.domain.aoe.ExplosionResult]
     * with the per-player damage list + affected block count.
     *
     * The gateway computes per-target damage by applying linear distance
     * falloff to [com.astarworks.astera.combat.domain.aoe.ExplosionSpec.damageProfile.base]
     * (full damage at center, `0.0` at the edge). The falloff curve is
     * intentionally simple in Phase 1 — Phase 6 may introduce a curve
     * descriptor on [com.astarworks.astera.combat.domain.aoe.ExplosionSpec]
     * to override.
     */
    public fun trigger(spec: ExplosionSpec): Result<ExplosionResult, ExplosionError>
}
