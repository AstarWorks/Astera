package com.astarworks.astera.core.application.service.survival

import com.astarworks.astera.core.application.port.outbound.survival.IGatheringYieldRoller
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.survival.GatheringSpot
import java.util.Random

/**
 * Weighted-pick [IGatheringYieldRoller] backed by [java.util.Random]
 * (Wave 5pve-survival / ADR-0107).
 *
 * One yield entry per harvest:
 *
 *  1. Sum the weights across `spot.yields`.
 *  2. Roll a uniform in `[0, total)`.
 *  3. Walk the yields advancing a cumulative weight; the first entry
 *     where `cumulative > roll` is the winner.
 *  4. Roll the count uniformly in `[min..max]` (inclusive).
 *
 * The seed is constructor-injected so deterministic tests can pin
 * outcomes; the default is `Random()` (system seed).
 */
public class UniformGatheringYieldRoller(
    private val random: Random = Random(),
) : IGatheringYieldRoller {

    override fun roll(spot: GatheringSpot): Map<ItemId, Int> {
        val totalWeight = spot.yields.sumOf { it.weight.toLong() }
        if (totalWeight <= 0L) return emptyMap()
        val pick = (random.nextDouble() * totalWeight.toDouble()).toLong()
        var cumulative = 0L
        var chosen = spot.yields.last()
        for (entry in spot.yields) {
            cumulative += entry.weight.toLong()
            if (pick < cumulative) {
                chosen = entry
                break
            }
        }
        val count = if (chosen.minCount == chosen.maxCount) {
            chosen.minCount
        } else {
            chosen.minCount + random.nextInt(chosen.maxCount - chosen.minCount + 1)
        }
        return mapOf(chosen.itemId to count)
    }
}
