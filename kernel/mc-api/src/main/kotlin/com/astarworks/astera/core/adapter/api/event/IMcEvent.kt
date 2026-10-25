package com.astarworks.astera.core.adapter.api.event

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ItemId
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

    /**
     * A raw player input on an item. [itemId] is the Astera item the input
     * acts on (the stack in hand for clicks, the dropped stack for [InputKind.DROP],
     * the bow for [InputKind.SHOOT]); `null` lets the consumer fall back to
     * reading the main hand. [sneaking] is sampled at the moment of input.
     */
    data class PlayerInput(
        override val player: IMcPlayer,
        val kind: InputKind,
        val sneaking: Boolean,
        val itemId: ItemId? = null,
    ) : IMcEvent

    enum class InputKind { LEFT_CLICK, RIGHT_CLICK, DROP, SHOOT }

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
