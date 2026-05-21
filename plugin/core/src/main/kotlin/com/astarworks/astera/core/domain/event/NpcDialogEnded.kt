package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.dialog.DialogEndReason
import com.astarworks.astera.core.domain.dialog.DialogTreeId
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * NPC dialog walk for [playerId] in [treeId] terminated with [reason]
 * (Wave 5p, ADR-0088).
 *
 * Published exactly once per started conversation — when the runner reaches a
 * [com.astarworks.astera.core.domain.dialog.DialogNode.End], when the player
 * cancels, when the player disconnects, when a condition's `ifFalse` branch
 * leads to an `End`, or when an admin reload drops the catalog row mid-walk
 * (see [DialogEndReason]).
 */
public data class NpcDialogEnded(
    public val playerId: PlayerId,
    public val treeId: DialogTreeId,
    public val reason: DialogEndReason,
    public val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent
