package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.animation.block.BlockBreakStage
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId
import java.time.Instant

/**
 * Block break-animation stage changed (Wave 5animation, ADR-0113).
 *
 * Published by [com.astarworks.astera.core.application.port.outbound.block.IBlockBreakAnimationGateway]
 * on `setBreakStage` when the stage actually changes (no-op when the
 * caller re-asserts the same stage — the gateway elides). Subscribers:
 *  - Replay capture (Wave 5w) — records mining progression.
 *  - Audio cue scheduler (Wave 5effects) — schedule per-stage crack
 *    sound for the canonical 10-stage progression.
 *  - Anti-cheat (Wave 5q) — abnormal mining-stage skipping detection.
 *
 * The [viewers] field, if non-null, identifies the player subset that saw
 * the change (per-viewer break animations let Astera show "your block"
 * only to the miner). A null value means the change was broadcast to
 * every viewer in render distance.
 *
 * @property world world id.
 * @property position block position.
 * @property previousStage stage before the change (null = previously unset / freshly entering).
 * @property newStage stage after the change.
 * @property viewers player subset that observed the change, or null for "all viewers".
 * @property atTick server tick at change time.
 */
public data class BlockBreakStageChanged(
    public val world: WorldId,
    public val position: Vec3,
    public val previousStage: BlockBreakStage?,
    public val newStage: BlockBreakStage,
    public val viewers: Set<PlayerId>?,
    public val atTick: Long,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Global
}
