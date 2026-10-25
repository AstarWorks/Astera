package com.astarworks.astera.core.application.service.mob

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.mob.ILootRollService
import com.astarworks.astera.core.domain.event.LootDropped
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.mob.LootTableId
import com.astarworks.astera.core.domain.mob.loot.LootDrop
import com.astarworks.astera.core.domain.mob.loot.LootRoll
import com.astarworks.astera.core.domain.mob.loot.LootRollContext
import kotlin.random.Random

/**
 * Phase 1 default impl of [ILootRollService] (Wave 5pve-boss, ADR-0098).
 *
 * Uses [kotlin.random.Random] seeded deterministically per call. Each row of
 * the table is rolled independently — call `rng.nextDouble() < drop.chance`
 * for each, and if it lands, `rng.nextInt(maxCount - minCount + 1) + minCount`
 * for the count. This matches HSB / OSRS multi-roll semantics.
 *
 * # Seed mixing
 *
 * The seed actually fed to [Random] is `(seed ^ tableHash ^ mobHash ^ killerHash)`
 * so two different tables / mobs / killers with the same input seed produce
 * different rolls. Replay reconstruction works because the recorded seed is
 * the **input** seed; the service re-mixes the same context fields to
 * reproduce the draw deterministically.
 *
 * The mixed seed value is what's returned in [LootRoll.seed] — callers that
 * want to replay should pass back the **input** seed (not the mixed one)
 * along with the same [LootRollContext]. We surface the mixed seed for
 * audit visibility ("which actual RNG state produced this draw").
 *
 * Publishes [LootDropped] before returning. Empty tables produce empty
 * rolls (still publishes the empty event so audit + analytics see the
 * "tried to roll but table was empty" signal).
 */
public class DeterministicLootRollService(
    private val eventBus: IDomainEventBus,
) : ILootRollService {

    private val tables: MutableMap<LootTableId, List<LootDrop>> = mutableMapOf()

    override fun registerTable(tableId: LootTableId, drops: List<LootDrop>) {
        tables[tableId] = drops.toList()
    }

    override fun tableOf(tableId: LootTableId): List<LootDrop> =
        tables[tableId] ?: emptyList()

    override fun roll(context: LootRollContext, seed: Long, atTick: Long): LootRoll {
        val mixedSeed = mixSeed(seed, context)
        val rng = Random(mixedSeed)
        val rows = tableOf(context.tableId)
        val accumulated: MutableMap<ItemId, Int> = LinkedHashMap()
        for (drop in rows) {
            // Always advance the RNG by one nextDouble() per row so adding
            // a guaranteed row doesn't shift the chance roll of subsequent
            // rows. The branch decides whether to consume the count roll.
            val chanceRoll = rng.nextDouble()
            if (chanceRoll < drop.chance) {
                val count = if (drop.isFixedCount) {
                    drop.minCount
                } else {
                    rng.nextInt(drop.maxCount - drop.minCount + 1) + drop.minCount
                }
                accumulated.merge(drop.itemId, count) { a, b -> a + b }
            }
        }
        val drops: List<Pair<ItemId, Int>> = accumulated.entries.map { (k, v) -> k to v }
        val result = LootRoll(drops = drops, seed = mixedSeed)

        eventBus.publish(
            LootDropped(
                mobId = context.mobId,
                bossId = context.bossId,
                tableId = context.tableId,
                killerId = context.killer,
                drops = drops,
                seed = mixedSeed,
                atTick = atTick,
            ),
        )
        return result
    }

    private fun mixSeed(seed: Long, context: LootRollContext): Long {
        var h = seed
        h = h xor context.tableId.value.hashCode().toLong()
        h = h xor context.mobId.value.hashCode().toLong()
        h = h xor context.killer.uuid.mostSignificantBits
        h = h xor context.killer.uuid.leastSignificantBits
        context.bossId?.let { h = h xor it.value.hashCode().toLong() }
        return h
    }
}
