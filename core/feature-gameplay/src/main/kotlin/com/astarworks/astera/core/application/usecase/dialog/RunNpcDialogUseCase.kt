package com.astarworks.astera.core.application.usecase.dialog

import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter
import com.astarworks.astera.core.application.port.inbound.INpcDialogService
import com.astarworks.astera.core.application.port.outbound.IDialogPredicateEvaluator
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.INpcDialogRegistry
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.dialog.DialogEndReason
import com.astarworks.astera.core.domain.dialog.DialogNode
import com.astarworks.astera.core.domain.dialog.DialogNodeId
import com.astarworks.astera.core.domain.dialog.DialogTreeId
import com.astarworks.astera.core.domain.dialog.NpcDialogError
import com.astarworks.astera.core.domain.dialog.NpcDialogTree
import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.event.NpcDialogEnded
import com.astarworks.astera.core.domain.event.NpcDialogNodeEntered
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Stateful per-player NPC dialog runner (Wave 5p, ADR-0088).
 *
 * Mirrors [com.astarworks.astera.core.application.usecase.tutorial.RunTutorialUseCase]
 * in shape: a `Map<PlayerId, RunnerState>` driven by the public API only.
 * Each transition publishes [NpcDialogNodeEntered]; the final transition
 * publishes [NpcDialogEnded] and removes the runner state.
 *
 * # Node semantics
 *
 *  - [DialogNode.Text]: `advance(null)` walks to `nextNodeId` or ends.
 *  - [DialogNode.Choice]: `advance(choiceIndex)` selects the option's next.
 *    `advance(null)` returns [NpcDialogError.IllegalAdvance].
 *  - [DialogNode.Condition]: evaluated **during** the transition that lands
 *    on it; the runner never "stops on" a Condition. The branch's target
 *    node is entered and published as the actual landing event.
 *  - [DialogNode.Intent]: dispatched via [intentRouter.dispatch], then
 *    auto-advances to `nextNodeId` (or ends).
 *  - [DialogNode.End]: terminal — publishes [NpcDialogEnded] with the
 *    end's [DialogEndReason] and clears the runner state.
 *
 * # Cycle / safety
 *
 * The runner enforces a `MAX_TRANSITIONS_PER_ADVANCE` cap (default 32) so a
 * pathological `Condition` chain (`A.ifTrue=A`) cannot lock the server in a
 * spin. Hitting the cap returns [NpcDialogError.IllegalAdvance] and ends the
 * runner with `DialogEndReason.CANCELLED`.
 *
 * # Threading
 *
 * Paper main-thread by convention (silly-knuth §6). The intent dispatch,
 * predicate evaluation, and event publish all run synchronously inside a
 * single `advance` call.
 */
public class RunNpcDialogUseCase(
    private val registry: INpcDialogRegistry,
    private val intentRouter: IGuiIntentRouter,
    private val predicateEvaluator: IDialogPredicateEvaluator,
    private val events: IDomainEventBus,
    private val scheduler: IScheduler,
    private val server: IMcServer,
    private val maxTransitionsPerAdvance: Int = 32,
) : INpcDialogService {

    /** Per-player runtime cursor (intentionally minimal). */
    public data class RunnerState(
        public val treeId: DialogTreeId,
        public val npcEntity: EntityHandle,
        public val currentNodeId: DialogNodeId,
    )

    private val state: MutableMap<PlayerId, RunnerState> = mutableMapOf()

    override suspend fun startTree(
        playerId: PlayerId,
        npcEntity: EntityHandle,
        treeId: DialogTreeId,
    ): Result<Unit, NpcDialogError> {
        if (state.containsKey(playerId)) {
            return Result.failure(NpcDialogError.AlreadyRunning(playerId))
        }
        val tree = registry.find(treeId)
            ?: return Result.failure(NpcDialogError.TreeNotInCatalog(treeId))

        return enterNode(playerId, npcEntity, tree, tree.rootNodeId)
    }

    override suspend fun advance(
        playerId: PlayerId,
        choiceIndex: Int?,
    ): Result<Unit, NpcDialogError> {
        val cursor = state[playerId]
            ?: return Result.failure(NpcDialogError.NotRunning(playerId))
        val tree = registry.find(cursor.treeId)
        if (tree == null) {
            // Catalog rug-pull (e.g., admin reload mid-walk).
            publishEnd(playerId, cursor.treeId, DialogEndReason.CATALOG_SWAPPED)
            state.remove(playerId)
            return Result.failure(NpcDialogError.TreeNotInCatalog(cursor.treeId))
        }
        val currentNode = tree.nodes[cursor.currentNodeId]
            ?: return Result.failure(
                NpcDialogError.NodeNotInTree(cursor.treeId, cursor.currentNodeId),
            )

        val nextId: DialogNodeId? = when (currentNode) {
            is DialogNode.Text -> currentNode.nextNodeId
            is DialogNode.Choice -> {
                val idx = choiceIndex
                    ?: return Result.failure(
                        NpcDialogError.IllegalAdvance("Choice node requires a choiceIndex"),
                    )
                val option = currentNode.options.getOrNull(idx)
                    ?: return Result.failure(
                        NpcDialogError.ChoiceIndexOutOfRange(
                            available = currentNode.options.size,
                            requested = idx,
                        ),
                    )
                option.nextNodeId
            }
            is DialogNode.Intent -> {
                // Resolve the player handle; if the player just disconnected,
                // skip the intent dispatch silently and end the dialog.
                val mcPlayer = server.findPlayer(playerId)
                if (mcPlayer != null) {
                    intentRouter.dispatch(mcPlayer, currentNode.intent)
                }
                currentNode.nextNodeId
            }
            is DialogNode.Condition ->
                // Condition nodes are walked through, never "stopped on"; if
                // we somehow landed on one, treat the advance as illegal.
                return Result.failure(
                    NpcDialogError.IllegalAdvance("advance called on a Condition node — should have been walked through automatically"),
                )
            is DialogNode.End -> {
                publishEnd(playerId, cursor.treeId, currentNode.reason)
                state.remove(playerId)
                return Result.success(Unit)
            }
        }
        if (nextId == null) {
            publishEnd(playerId, cursor.treeId, DialogEndReason.COMPLETED)
            state.remove(playerId)
            return Result.success(Unit)
        }
        return enterNode(playerId, cursor.npcEntity, tree, nextId)
    }

    override suspend fun cancel(playerId: PlayerId): Result<Unit, NpcDialogError> {
        val cursor = state.remove(playerId)
            ?: return Result.failure(NpcDialogError.NotRunning(playerId))
        publishEnd(playerId, cursor.treeId, DialogEndReason.CANCELLED)
        return Result.success(Unit)
    }

    /**
     * Walk through Condition nodes until landing on a stoppable node. Each
     * transition publishes a [NpcDialogNodeEntered] for the final landed
     * node only — conditions are invisible to the HUD.
     */
    private suspend fun enterNode(
        playerId: PlayerId,
        npcEntity: EntityHandle,
        tree: NpcDialogTree,
        startId: DialogNodeId,
    ): Result<Unit, NpcDialogError> {
        var currentId = startId
        var transitions = 0
        while (true) {
            if (transitions++ > maxTransitionsPerAdvance) {
                publishEnd(playerId, tree.id, DialogEndReason.CANCELLED)
                state.remove(playerId)
                return Result.failure(
                    NpcDialogError.IllegalAdvance(
                        "exceeded $maxTransitionsPerAdvance node transitions in a single advance — possible cycle in '${tree.id}'",
                    ),
                )
            }
            val node = tree.nodes[currentId]
                ?: return Result.failure(NpcDialogError.NodeNotInTree(tree.id, currentId))

            if (node is DialogNode.Condition) {
                val branch = predicateEvaluator.evaluate(playerId, node.predicate)
                currentId = if (branch) node.ifTrue else node.ifFalse
                continue
            }

            // Stoppable node — install state, publish, return.
            state[playerId] = RunnerState(tree.id, npcEntity, currentId)
            events.publish(
                NpcDialogNodeEntered(
                    playerId = playerId,
                    treeId = tree.id,
                    nodeId = currentId,
                    node = node,
                    atTick = scheduler.currentTick(),
                ),
            )
            // If the landed node is End, the next advance() will publish the
            // ended event. But we may also be able to fast-path here: an End
            // is a 1-step terminal and the player has nothing to advance.
            // Keeping the explicit advance() preserves the symmetry "every
            // step requires at least one advance to consume" which simpler
            // HUD code can rely on.
            return Result.success(Unit)
        }
    }

    private fun publishEnd(playerId: PlayerId, treeId: DialogTreeId, reason: DialogEndReason) {
        events.publish(
            NpcDialogEnded(
                playerId = playerId,
                treeId = treeId,
                reason = reason,
                atTick = scheduler.currentTick(),
            ),
        )
    }

    /** Read-only peek at the current node id, or null if no dialog is running. */
    public fun currentNodeId(playerId: PlayerId): DialogNodeId? = state[playerId]?.currentNodeId
}
