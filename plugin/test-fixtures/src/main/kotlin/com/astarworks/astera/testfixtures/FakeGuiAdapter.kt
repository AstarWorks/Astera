package com.astarworks.astera.testfixtures

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.domain.gui.GuiSpec
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * In-memory [IGuiAdapter] for use case tests.
 *
 * Records every [open] / [update] / [close] call as a [GuiOperation] in a
 * per-player history so tests can assert both the **final state**
 * ([currentSpec]) and the **call ordering** ([history]). Sessions are keyed
 * by [IMcPlayer.id] — the [IMcPlayer] reference itself is not retained.
 *
 * Per ADR-0021, the production adapter owns the last spec shown for every
 * player and diffs against it internally. This fake mirrors that ownership
 * model: [open] starts a session, [update] mutates the current spec (no-op
 * if no session), [close] terminates it.
 *
 * Thread-agnostic by design — domain/application tests don't simulate the
 * Paper main-thread requirement called out by the port contract.
 */
public class FakeGuiAdapter : IGuiAdapter {

    /** A single recorded operation on a player's GUI session. */
    public sealed class GuiOperation {
        public data class Open(val spec: GuiSpec) : GuiOperation()
        public data class Update(val spec: GuiSpec) : GuiOperation()
        public data object Close : GuiOperation()
    }

    private val current: MutableMap<PlayerId, GuiSpec> = mutableMapOf()
    private val log: MutableMap<PlayerId, MutableList<GuiOperation>> = mutableMapOf()

    /**
     * When non-`true`, [open] returns this value instead of `true` and the
     * session is not started. Use for failure-path tests (e.g. "player
     * offline").
     */
    public var openResult: Boolean = true

    override fun open(player: IMcPlayer, spec: GuiSpec): Boolean {
        if (!openResult) return false
        current[player.id] = spec
        logOp(player.id, GuiOperation.Open(spec))
        return true
    }

    override fun update(player: IMcPlayer, next: GuiSpec) {
        if (current[player.id] == null) return
        current[player.id] = next
        logOp(player.id, GuiOperation.Update(next))
    }

    override fun close(player: IMcPlayer) {
        if (current.remove(player.id) == null) return
        logOp(player.id, GuiOperation.Close)
    }

    override fun currentSpec(player: IMcPlayer): GuiSpec? = current[player.id]

    /**
     * Chronological list of every operation recorded for [player]. Empty if
     * no operation has touched this player yet.
     */
    public fun history(player: IMcPlayer): List<GuiOperation> =
        log[player.id]?.toList() ?: emptyList()

    private fun logOp(playerId: PlayerId, op: GuiOperation) {
        log.getOrPut(playerId) { mutableListOf() } += op
    }
}
