package com.astarworks.astera.weapons.application.service

import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.service.InMemoryCooldownTracker as CoreInMemoryCooldownTracker

/**
 * Weapons-plugin factory helper.
 *
 * The canonical implementation now lives in
 * [com.astarworks.astera.core.application.service.InMemoryCooldownTracker].
 * This file re-exports it as a convenience so the weapons plugin wiring
 * ([com.astarworks.astera.weapons.platform.AsteraWeaponsPlugin]) can keep a
 * short unqualified reference.
 *
 * Callers should migrate to the core import directly.
 */
public fun buildCooldownTracker(scheduler: IScheduler): com.astarworks.astera.core.application.port.outbound.ICooldownTracker =
    CoreInMemoryCooldownTracker(scheduler)
