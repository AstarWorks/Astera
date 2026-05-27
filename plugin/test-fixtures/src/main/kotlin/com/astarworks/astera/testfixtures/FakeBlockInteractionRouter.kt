package com.astarworks.astera.testfixtures

import com.astarworks.astera.core.application.port.outbound.block.BlockBindingHandle
import com.astarworks.astera.core.application.port.outbound.block.BlockInteractionAction
import com.astarworks.astera.core.application.port.outbound.block.IBlockInteractionRouter
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.BlockInteractAction
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Test fake for [IBlockInteractionRouter] (Wave 5block-embed, ADR-0120).
 *
 * Mirrors the in-memory production impl bit-for-bit; the separate fake
 * exists so tests can assert against captured binds via [bindLog] without
 * coupling to production state (the production impl can grow caches or
 * indexes without breaking tests).
 */
public class FakeBlockInteractionRouter : IBlockInteractionRouter {

    public enum class Channel { RIGHT, LEFT }

    public data class BindLog(
        public val world: WorldId,
        public val position: Vec3,
        public val channel: Channel,
        public val action: BlockInteractionAction,
        public val handle: BlockBindingHandle,
    )

    public val bindLog: MutableList<BindLog> = mutableListOf()
    public val unbindLog: MutableList<BlockBindingHandle> = mutableListOf()

    private data class Slot(val world: WorldId, val position: Vec3, val channel: Channel)
    private val bindings: MutableMap<Slot, Pair<BlockBindingHandle, BlockInteractionAction>> = mutableMapOf()

    override fun bindRightClick(
        world: WorldId,
        position: Vec3,
        action: BlockInteractionAction,
    ): BlockBindingHandle = bind(world, position, Channel.RIGHT, action)

    override fun bindLeftClick(
        world: WorldId,
        position: Vec3,
        action: BlockInteractionAction,
    ): BlockBindingHandle = bind(world, position, Channel.LEFT, action)

    private fun bind(
        world: WorldId,
        position: Vec3,
        channel: Channel,
        action: BlockInteractionAction,
    ): BlockBindingHandle {
        val handle = BlockBindingHandle.random()
        bindings[Slot(world, position, channel)] = handle to action
        bindLog += BindLog(world, position, channel, action, handle)
        return handle
    }

    override fun unbind(handle: BlockBindingHandle): Boolean {
        unbindLog += handle
        val key = bindings.entries.firstOrNull { it.value.first == handle }?.key ?: return false
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
        return bindings[Slot(world, position, channel)]?.second
    }
}
