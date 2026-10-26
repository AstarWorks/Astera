package com.astarworks.astera.match.application.service

import com.astarworks.astera.core.application.port.outbound.EventDelivery

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.domain.event.BlockBroken
import com.astarworks.astera.core.domain.event.BlockPlaced
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.world.BlockChange
import com.astarworks.astera.core.domain.world.BlockType
import org.slf4j.LoggerFactory

/**
 * Per-match in-memory journal of block mutations (Wave 5d, ADR-0033).
 *
 * Subscribes to [BlockPlaced] and [BlockBroken] domain events; maintains a
 * bounded `ArrayDeque<BlockChange>` per match (cap [MAX_PER_MATCH], oldest
 * evicted on overflow). [RestoreBlocksOnMatchEndedUseCase][
 * com.astarworks.astera.match.application.usecase.match.RestoreBlocksOnMatchEndedUseCase]
 * consumes the journal on `MatchEnded` to revert per-match block changes.
 *
 * The 5000-per-match cap is a Phase 1 PoC limitation — heavy matches can
 * lose older entries (acceptable: the most-recently-modified blocks are
 * the ones players notice). Wave 7+ may upgrade to a Postgres-backed
 * journal per ADR-0033 §Consequences.
 *
 * Eviction is FIFO: when the deque hits [MAX_PER_MATCH], the oldest entry
 * is dropped. A long-lived match with constant digging will see early
 * blocks NOT restored — the journal logs at WARN once per match the first
 * time eviction occurs so this is visible in ops monitoring.
 *
 * ## Invalid blockId handling
 *
 * Events carry `blockId: String`. The journal wraps the raw id into a
 * [BlockType] (validates `namespace:identifier`); if the id is malformed
 * we log a warning and skip the entry rather than crash — block events
 * may originate from Bukkit adapters where a future Material rename or a
 * custom-block provider could legitimately produce an id outside the
 * validation regex.
 *
 * ## Thread-safety
 *
 * **Not thread-safe** — Astera invariant: all match-state mutation runs on
 * the server main thread (per `IScheduler` regional contract). The plain
 * `MutableMap` + `ArrayDeque` are intentional; concurrent collections
 * would imply support we do not provide.
 */
public class BlockChangeJournal {

    private val logger = LoggerFactory.getLogger(BlockChangeJournal::class.java)
    private val byMatch: MutableMap<MatchId, ArrayDeque<BlockChange>> = mutableMapOf()
    private val evictionWarned: MutableSet<MatchId> = mutableSetOf()

    /**
     * Subscribe to [BlockPlaced] + [BlockBroken]. Returns the subscriptions
     * so the caller (Phase C `AsteraMatchPlugin.onDisable`) can cancel them.
     */
    public fun subscribe(bus: IDomainEventBus): List<IDomainEventBus.Subscription> = listOf(
        bus.subscribe(BlockPlaced::class, EventDelivery.Global) { event -> onPlaced(event) },
        bus.subscribe(BlockBroken::class, EventDelivery.Global) { event -> onBroken(event) },
    )

    /** Visible for tests; exercised through [subscribe] in production. */
    public fun onPlaced(event: BlockPlaced) {
        val blockType = parseBlockType(event.blockId) ?: return
        append(
            event.matchId,
            BlockChange.Placed(
                position = event.position,
                blockType = blockType,
                byPlayer = event.playerId,
                atTick = event.atTick,
            ),
        )
    }

    /** Visible for tests; exercised through [subscribe] in production. */
    public fun onBroken(event: BlockBroken) {
        val blockType = parseBlockType(event.blockId) ?: return
        append(
            event.matchId,
            BlockChange.Broken(
                position = event.position,
                prevBlockType = blockType,
                byPlayer = event.playerId,
                atTick = event.atTick,
            ),
        )
    }

    private fun append(matchId: MatchId, change: BlockChange) {
        val deque = byMatch.getOrPut(matchId) { ArrayDeque() }
        if (deque.size >= MAX_PER_MATCH) {
            deque.removeFirst()
            if (evictionWarned.add(matchId)) {
                logger.warn(
                    "BlockChangeJournal for match {} hit cap {}; oldest entries will be evicted (not restored)",
                    matchId,
                    MAX_PER_MATCH,
                )
            }
        }
        deque.addLast(change)
    }

    private fun parseBlockType(raw: String): BlockType? =
        runCatching { BlockType(raw) }
            .onFailure {
                logger.warn(
                    "BlockChangeJournal: invalid blockId '{}' ({}); skipping entry",
                    raw,
                    it.message,
                )
            }
            .getOrNull()

    /** Snapshot of all recorded changes for [matchId] in insertion order (oldest first). */
    public fun snapshot(matchId: MatchId): List<BlockChange> =
        byMatch[matchId]?.toList() ?: emptyList()

    /** Drop all journal state for [matchId] (called after restoration). */
    public fun clear(matchId: MatchId) {
        byMatch.remove(matchId)
        evictionWarned.remove(matchId)
    }

    public companion object {
        /** Maximum journal entries retained per match before FIFO eviction kicks in. */
        public const val MAX_PER_MATCH: Int = 5000
    }
}
