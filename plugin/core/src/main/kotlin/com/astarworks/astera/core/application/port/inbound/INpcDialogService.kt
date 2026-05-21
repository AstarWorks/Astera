package com.astarworks.astera.core.application.port.inbound

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.dialog.DialogTreeId
import com.astarworks.astera.core.domain.dialog.NpcDialogError
import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Inbound port for NPC dialog orchestration (Wave 5p, ADR-0088).
 *
 * Single facade exposed to:
 *
 *  - Wave 6b Paper NPC-click listener (`PaperNpcInteractionListener` →
 *    `INpcDialogBinding.resolve(handle)` → `startTree(...)`).
 *  - The dialog HUD adapter when the player clicks a choice option
 *    (`advance(playerId, choiceIndex)`).
 *  - Player disconnect listener (`cancel(playerId)`).
 *
 * Backed by `RunNpcDialogUseCase` which owns the per-player runner state
 * map. See [com.astarworks.astera.core.application.usecase.dialog.RunNpcDialogUseCase].
 */
public interface INpcDialogService {

    /**
     * Begin walking the tree identified by [treeId] for [playerId], anchored at
     * the NPC [npcEntity] (so subscribers can render the dialog with the right
     * NPC head / name). Fails if a dialog is already running for the player or
     * the tree is unknown.
     */
    public fun startTree(
        playerId: PlayerId,
        npcEntity: EntityHandle,
        treeId: DialogTreeId,
    ): Result<Unit, NpcDialogError>

    /**
     * Advance the player's current dialog. For [com.astarworks.astera.core.domain.dialog.DialogNode.Text]
     * and [com.astarworks.astera.core.domain.dialog.DialogNode.Intent], pass
     * `choiceIndex = null` (the node defines its own next). For
     * [com.astarworks.astera.core.domain.dialog.DialogNode.Choice], pass the
     * 0-based index of the picked option.
     *
     * Condition nodes do not require an explicit advance — the runner walks
     * through them when reaching them on the previous transition.
     */
    public fun advance(
        playerId: PlayerId,
        choiceIndex: Int? = null,
    ): Result<Unit, NpcDialogError>

    /** Cancel the player's in-flight dialog (player escape / disconnect). */
    public fun cancel(playerId: PlayerId): Result<Unit, NpcDialogError>
}
