package com.astarworks.astera.core.domain.animation.block

/**
 * Failure modes of [com.astarworks.astera.core.application.port.outbound.block.IBlockBreakAnimationGateway]
 * (Wave 5animation, ADR-0113).
 *
 * Sealed for the same reason as [com.astarworks.astera.core.domain.animation.player.PlayerAnimationError] —
 * keeps the application API surface free of raw JVM throwables.
 */
public sealed class BlockBreakAnimationError {

    /** Generic platform failure (Bukkit-side packet exception in the Paper adapter). */
    public data class Platform(public val detail: String) : BlockBreakAnimationError()
}
