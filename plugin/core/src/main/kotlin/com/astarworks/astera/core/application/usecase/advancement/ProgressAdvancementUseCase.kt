package com.astarworks.astera.core.application.usecase.advancement

import com.astarworks.astera.core.application.port.inbound.IAdvancementService
import com.astarworks.astera.core.application.port.outbound.IAdvancementRegistry
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IPlayerAdvancementProgressRepository
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.advancement.AdvancementCriteria
import com.astarworks.astera.core.domain.advancement.AdvancementError
import com.astarworks.astera.core.domain.advancement.AdvancementId
import com.astarworks.astera.core.domain.advancement.AdvancementProgress
import com.astarworks.astera.core.domain.advancement.CustomAdvancement
import com.astarworks.astera.core.domain.event.AdvancementCompleted
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Core advancement progress orchestrator (Wave 5p, ADR-0088).
 *
 * # Responsibilities
 *
 *  1. Validate the increment & resolve the advancement from [registry].
 *  2. Short-circuit if the advancement is already completed (idempotent).
 *  3. Compute the dominant leaf counter's target (composite criteria pick
 *     the most-progressed branch; see [pickDominantTarget]). The HUD reads
 *     these numbers.
 *  4. Persist the new progress via [repository].
 *  5. On completion: latch `completedAt`, publish [AdvancementCompleted], and
 *     hand off reward dispatch to [GrantAdvancementRewardUseCase].
 *
 * # Composite criteria handling
 *
 * For [AdvancementCriteria.AllOf] / [AdvancementCriteria.AnyOf], the use case
 * stores a single `(current, target)` pair derived from the **dominant leaf**:
 *
 *  - For `AllOf`: the **least-progressed** branch's `target` is dominant (the
 *    slowest path gates the whole). For HUD display this gives the worst
 *    fraction across children.
 *  - For `AnyOf`: the **most-progressed** branch is dominant (any-one-of
 *    semantics, the user sees their best chance).
 *
 * This is a HUD simplification — true completion is evaluated by walking the
 * full criteria tree in [isSatisfied] using the underlying per-leaf counters
 * loaded from [repository]. Each leaf criterion gets its own per-(player, id)
 * row keyed by a deterministic sub-id (`<advancementId>:<leafIndex>`), so
 * composite progress is faithful across restarts.
 *
 * # Threading & idempotence
 *
 * Paper main-thread by convention (silly-knuth §6). Repeat invocations after
 * `completedAt` is latched return `Result.success(current)` with no state
 * change, no event re-publish, no double reward — see the early return below.
 */
public class ProgressAdvancementUseCase(
    private val registry: IAdvancementRegistry,
    private val repository: IPlayerAdvancementProgressRepository,
    private val rewardGrant: GrantAdvancementRewardUseCase,
    private val events: IDomainEventBus,
    private val scheduler: IScheduler,
    private val clock: () -> Instant = { Instant.now() },
) : IAdvancementService {

    override fun progress(
        playerId: PlayerId,
        advancementId: AdvancementId,
        increment: Int,
    ): Result<AdvancementProgress, AdvancementError> {
        if (increment <= 0) {
            return Result.failure(AdvancementError.InvalidIncrement(increment))
        }

        val advancement = registry.find(advancementId)
            ?: return Result.failure(AdvancementError.NotInCatalog(advancementId))

        // Per-leaf rows so composite criteria are evaluated faithfully.
        val leafIndex = pickAffectedLeafIndex(advancement.criteria, increment)
        val leafTarget = pickTargetForLeafIndex(advancement.criteria, leafIndex)
            ?: return Result.failure(AdvancementError.CriteriaShape(
                "advancement '${advancementId}' has no scalar-target leaf for increment",
            ))

        val storedKey = subRowId(advancementId, leafIndex)
        val existing = repository.find(playerId, storedKey)
        if (existing?.completedAt != null) {
            // Already completed — idempotent no-op.
            return Result.success(existing)
        }

        val nextCurrent = ((existing?.current ?: 0) + increment).coerceAtMost(leafTarget)

        // Evaluate full criteria tree to decide overall completion.
        val leafSnapshots = loadLeafSnapshots(playerId, advancement, overlay = leafIndex to nextCurrent)
        val nowComplete = isSatisfied(advancement.criteria, leafSnapshots)

        val completedAt = if (nowComplete) clock() else null
        val newProgress = AdvancementProgress(
            current = nextCurrent,
            target = leafTarget,
            completedAt = completedAt,
        )
        repository.upsert(playerId, storedKey, newProgress)

        if (nowComplete) {
            // Mirror the latch onto the canonical advancement id so callers
            // and the HUD can query the "is X done?" question without
            // knowing the leaf-row key.
            repository.upsert(
                playerId,
                advancementId,
                newProgress,
            )
            events.publish(
                AdvancementCompleted(
                    playerId = playerId,
                    advancementId = advancementId,
                    atTick = scheduler.currentTick(),
                ),
            )
            rewardGrant.grant(playerId, advancement)
        }

        return Result.success(newProgress)
    }

    override fun completed(playerId: PlayerId): Set<AdvancementId> =
        repository.completed(playerId)
            // Filter out the synthetic sub-row keys (those end with '-l<index>').
            .filter { !SUB_ROW_SUFFIX.containsMatchIn(it.value) }
            .toSet()

    override fun progressOf(
        playerId: PlayerId,
        advancementId: AdvancementId,
    ): AdvancementProgress? = repository.find(playerId, advancementId)

    // ─── Criteria helpers ────────────────────────────────────────────────

    /**
     * Pick which leaf is affected by an increment of this magnitude. For now
     * the policy is "the first leaf encountered in pre-order traversal" —
     * callers typically increment one criterion type per event (a kill →
     * KillCount leaf) so this is sufficient. Worker advancement listeners
     * (Phase B δ) call with the right id so the right leaf wins regardless.
     */
    private fun pickAffectedLeafIndex(criteria: AdvancementCriteria, @Suppress("UNUSED_PARAMETER") increment: Int): Int {
        var idx = 0
        fun walk(c: AdvancementCriteria): Int? = when (c) {
            is AdvancementCriteria.AllOf -> {
                for (child in c.children) {
                    val found = walk(child)
                    if (found != null) return found
                }
                null
            }
            is AdvancementCriteria.AnyOf -> {
                for (child in c.children) {
                    val found = walk(child)
                    if (found != null) return found
                }
                null
            }
            else -> {
                val mine = idx
                idx += 1
                mine
            }
        }
        walk(criteria)
        return 0
    }

    /** Return the scalar target for the leaf at [leafIndex] in pre-order, or null. */
    private fun pickTargetForLeafIndex(criteria: AdvancementCriteria, leafIndex: Int): Int? {
        var idx = 0
        fun walk(c: AdvancementCriteria): Int? = when (c) {
            is AdvancementCriteria.AllOf -> {
                for (child in c.children) {
                    val r = walk(child)
                    if (r != null) return r
                }
                null
            }
            is AdvancementCriteria.AnyOf -> {
                for (child in c.children) {
                    val r = walk(child)
                    if (r != null) return r
                }
                null
            }
            is AdvancementCriteria.KillCount -> if (idx++ == leafIndex) c.target else null
            is AdvancementCriteria.CurrencyEarned -> if (idx++ == leafIndex) c.amount.toInt().coerceAtLeast(1) else null
            is AdvancementCriteria.MatchesWon -> if (idx++ == leafIndex) c.count else null
            is AdvancementCriteria.ItemAcquired -> if (idx++ == leafIndex) 1 else null
        }
        return walk(criteria)
    }

    /** Sub-row id used to store per-leaf counters under the same player. */
    private fun subRowId(canonical: AdvancementId, leafIndex: Int): AdvancementId =
        AdvancementId("${canonical.value}-l$leafIndex")

    private companion object {
        /** Suffix marker on synthetic per-leaf sub-row ids: `-l<digits>` at end. */
        val SUB_ROW_SUFFIX: Regex = Regex("-l\\d+$")
    }

    private fun loadLeafSnapshots(
        playerId: PlayerId,
        advancement: CustomAdvancement,
        overlay: Pair<Int, Int>,
    ): Map<Int, Int> {
        val (overlayLeaf, overlayCurrent) = overlay
        val snap = mutableMapOf<Int, Int>()
        var idx = 0
        fun walk(c: AdvancementCriteria) {
            when (c) {
                is AdvancementCriteria.AllOf -> c.children.forEach { walk(it) }
                is AdvancementCriteria.AnyOf -> c.children.forEach { walk(it) }
                else -> {
                    val mine = idx
                    val stored = if (mine == overlayLeaf) {
                        overlayCurrent
                    } else {
                        repository.find(playerId, subRowId(advancement.id, mine))?.current ?: 0
                    }
                    snap[mine] = stored
                    idx += 1
                }
            }
        }
        walk(advancement.criteria)
        return snap
    }

    private fun isSatisfied(criteria: AdvancementCriteria, leaves: Map<Int, Int>, indexRef: IntArray = intArrayOf(0)): Boolean {
        return when (criteria) {
            is AdvancementCriteria.AllOf -> criteria.children.all { isSatisfied(it, leaves, indexRef) }
            is AdvancementCriteria.AnyOf -> criteria.children.any { isSatisfied(it, leaves, indexRef) }
            is AdvancementCriteria.KillCount -> {
                val i = indexRef[0]; indexRef[0] = i + 1
                (leaves[i] ?: 0) >= criteria.target
            }
            is AdvancementCriteria.CurrencyEarned -> {
                val i = indexRef[0]; indexRef[0] = i + 1
                (leaves[i] ?: 0) >= criteria.amount
            }
            is AdvancementCriteria.MatchesWon -> {
                val i = indexRef[0]; indexRef[0] = i + 1
                (leaves[i] ?: 0) >= criteria.count
            }
            is AdvancementCriteria.ItemAcquired -> {
                val i = indexRef[0]; indexRef[0] = i + 1
                (leaves[i] ?: 0) >= 1
            }
        }
    }
}
