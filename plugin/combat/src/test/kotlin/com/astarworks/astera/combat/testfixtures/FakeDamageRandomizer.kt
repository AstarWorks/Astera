package com.astarworks.astera.combat.testfixtures

import com.astarworks.astera.combat.application.port.outbound.IDamageRandomizer

/**
 * Test fixture: returns values from [queue] in order, cycling on overflow.
 *
 * Used by `CriticalStrikeModifierTest` to force crit / no-crit branches
 * deterministically.
 */
public class FakeDamageRandomizer(
    private val queue: List<Double>,
) : IDamageRandomizer {
    private var index = 0

    override fun nextDouble(): Double {
        if (queue.isEmpty()) return 0.0
        val v = queue[index % queue.size]
        index++
        return v
    }
}
