package com.astarworks.astera.core.adapter.api.event

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Vendor-neutral player / world interaction events.
 *
 * Concrete server cores (Paper, Folia, ...) translate their native events
 * into [IMcEvent] subclasses. Application-layer use cases listen via simple
 * callbacks; no Bukkit Listener interface leaks past this boundary.
 *
 * Damage and death events are deliberately NOT modelled here — Astera's
 * canonical flow is `ApplyDamageUseCase` / `PlayerKilled` domain event
 * (see ADR-0030 §8 for the Wave 6 Bukkit hijack commitment).
 */
sealed interface IMcEvent {
    val player: IMcPlayer

    data class PlayerLeftClickAir(override val player: IMcPlayer) : IMcEvent
    data class PlayerLeftClickBlock(override val player: IMcPlayer) : IMcEvent

    data class PlayerJoined(override val player: IMcPlayer) : IMcEvent
    data class PlayerQuit(override val player: IMcPlayer) : IMcEvent

    data class BlockPlaced(
        override val player: IMcPlayer,
        val worldId: WorldId,
        val at: Vec3,
        val blockType: BlockType,
    ) : IMcEvent

    data class BlockBroken(
        override val player: IMcPlayer,
        val worldId: WorldId,
        val at: Vec3,
        val blockType: BlockType,
        val toolMaterialKey: String?,
    ) : IMcEvent
}
