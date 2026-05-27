package com.astarworks.astera.core.application.service.block

import com.astarworks.astera.core.application.port.outbound.block.BlockBindingHandle
import com.astarworks.astera.core.application.port.outbound.block.BlockInteractionAction
import com.astarworks.astera.core.application.port.outbound.block.IBlockInteractionRouter
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.BlockInteractAction
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Default in-memory [IBlockInteractionRouter] (Wave 5block-embed, ADR-0120).
 *
 * Wired by `AsteraCorePlugin` at priority 0. There is no Wave 6 Paper
 * adapter for this port — bindings are server-side state owned by stage
 * authors / use cases, not by Bukkit; the upcoming Postgres overlay (Wave
 * 6b candidate) would persist bindings across restart but the contract
 * itself is platform-free.
 *
 * # Binding key
 *
 * `(world, position, channel)` uniquely identifies a slot, where
 * `channel ∈ {RightClick, LeftClick}`. The implementation uses a single
 * map keyed by [Slot]; an `add` for an existing slot overwrites the prior
 * binding so the new handle is the sole owner. The previous handle is
 * invalidated (subsequent `unbind` returns `false`).
 *
 * # Position equality
 *
 * Block positions are domain [Vec3] (double precision). Callers SHOULD pass
 * block-aligned coordinates (`floor`-style), since `Vec3.equals` is structural
 * and a 0.5-block offset would never resolve. Phase 1 stage authoring tooling
 * is responsible for normalising; the router does not snap.
 *
 * # Lookup channel mapping
 *
 * Only [BlockInteractAction.RightClick] and [BlockInteractAction.LeftClick]
 * resolve. Other variants return `null` (the routing listener short-circuits
 * before invoking the dispatcher).
 */
public class InMemoryBlockInteractionRouter : IBlockInteractionRouter {

    private enum class Channel { RIGHT, LEFT }

    private data class Slot(
        val world: WorldId,
        val position: Vec3,
        val channel: Channel,
    )

    private val bindings: MutableMap<Slot, Entry> = mutableMapOf()

    private data class Entry(
        val handle: BlockBindingHandle,
        val action: BlockInteractionAction,
    )

    override fun bindRightClick(
        world: WorldId,
        position: Vec3,
        action: BlockInteractionAction,
    ): BlockBindingHandle = bind(Slot(world, position, Channel.RIGHT), action)

    override fun bindLeftClick(
        world: WorldId,
        position: Vec3,
        action: BlockInteractionAction,
    ): BlockBindingHandle = bind(Slot(world, position, Channel.LEFT), action)

    private fun bind(slot: Slot, action: BlockInteractionAction): BlockBindingHandle {
        val handle = BlockBindingHandle.random()
        bindings[slot] = Entry(handle, action)
        return handle
    }

    override fun unbind(handle: BlockBindingHandle): Boolean {
        val key = bindings.entries.firstOrNull { it.value.handle == handle }?.key ?: return false
        bindings.remove(key)
        return true
    }

    override fun lookup(
        world: WorldId,
        position: Vec3,
        action: BlockInteractAction,
    ): BlockInteractionAction? {
        val channel = when (action) {
            BlockInteractAction.RightClick -> Channel.RIGHT
            BlockInteractAction.LeftClick -> Channel.LEFT
            BlockInteractAction.PhysicalContact,
            BlockInteractAction.OffHandUse,
            -> return null
        }
        return bindings[Slot(world, position, channel)]?.action
    }

    /** Test helper: total binding count across both channels. */
    public val size: Int get() = bindings.size
}
