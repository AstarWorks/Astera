@file:Suppress("DEPRECATION")

package com.astarworks.astera.weapons.application.port.outbound

/**
 * Deprecated alias kept for source compatibility during the R0.c migration.
 *
 * All new code should import and use
 * [com.astarworks.astera.core.application.port.outbound.ICooldownTracker]
 * with [com.astarworks.astera.core.domain.cooldown.CooldownKey] directly.
 *
 * This alias will be removed once all call sites are updated.
 */
@Deprecated(
    message = "Use com.astarworks.astera.core.application.port.outbound.ICooldownTracker instead.",
    replaceWith = ReplaceWith(
        "ICooldownTracker",
        "com.astarworks.astera.core.application.port.outbound.ICooldownTracker",
    ),
)
public typealias ICooldownTracker = com.astarworks.astera.core.application.port.outbound.ICooldownTracker
