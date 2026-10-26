package com.astarworks.astera.core.domain.block

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Sealed failure modes for `IBlockStateGateway.applyDamageFromSkill` and
 * `placeCustomBlock` (Wave 5x, ADR-0090).
 *
 * Per `coding-posture.md` "no raw JVM throwables in application API", every
 * gateway-level failure surfaces as a typed `BlockGatewayError`. The
 * [BlockDamageOutcome.Ineffective] variant is *not* an error — it's a
 * successful "no-op" outcome distinct from these structural failures.
 */
public sealed interface BlockGatewayError {

    /** Custom block placement attempted at a position already occupied by a different custom block. */
    public data class PositionOccupied(
        val world: WorldId,
        val pos: Vec3,
        val existing: BlockType,
    ) : BlockGatewayError

    /** [BlockType] was supplied to a place call but no [CustomBlockSpec] exists for it. */
    public data class UnknownCustomBlock(val type: BlockType) : BlockGatewayError

    /** Storage-layer failure (Postgres flush, in-memory map corruption). */
    public data class Storage(val reason: String) : BlockGatewayError
}
