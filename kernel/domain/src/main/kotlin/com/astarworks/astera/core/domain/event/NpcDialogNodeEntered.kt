package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.dialog.DialogNode
import com.astarworks.astera.core.domain.dialog.DialogNodeId
import com.astarworks.astera.core.domain.dialog.DialogTreeId
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Runner advanced [playerId] to [nodeId] in [treeId] (Wave 5p, ADR-0088).
 *
 * Published synchronously by the dialog runner on each node transition.
 * Subscribers:
 *
 *  - Wave 6a HUD adapter: renders the node payload (text / choice prompt /
 *    intent action) in the player-facing surface.
 *  - Analytics / audit: records conversation flow for funnel analysis.
 *  - Tutorial sequencer: consumes this event when its current step is
 *    `AwaitDialogNode(nodeId)`.
 *
 * The full [node] is included so subscribers don't need to re-resolve through
 * the registry (the registry could swap mid-walk on `/astera reload dialogs`).
 */
public data class NpcDialogNodeEntered(
    public val playerId: PlayerId,
    public val treeId: DialogTreeId,
    public val nodeId: DialogNodeId,
    public val node: DialogNode,
    public val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(playerId.uuid)
}
