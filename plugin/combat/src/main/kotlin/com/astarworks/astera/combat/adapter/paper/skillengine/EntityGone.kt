package com.astarworks.astera.combat.adapter.paper.skillengine

import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The one rule for the Paper skill gateways: a request that names an entity the world no longer has is a legitimate world outcome
 * (the victim died, logged out or unloaded between the row being queued and played), so the gateway skips it. Every such skip goes through
 * [skipped], which counts it per call site and logs it at debug, so no early return is silent. Anything else that cannot be done
 * (missing wiring, malformed ids, unknown operations) is NOT an outcome of the world and throws a named error instead.
 *
 * Call sites that skip through [skipped] (keep this list complete; Konsist does not see it):
 *  - movement.teleport / addVelocity / invulnerable / faceMovement : caster or target not a loaded living entity
 *  - gameplay.hurt                                                  : target not a loaded living entity
 *  - gameplay.restoreResource(health)                               : entity not loaded, invalid or dead
 *  - gameplay.setHeldModel / clearHeldModel                         : caster offline, or main hand empty
 *  - victimAnim.play                                                : victim not a loaded living entity
 * Other legitimate outcomes that are logged by name where they happen (not through here): damage refused by the pipeline
 * (immunity, i-frames: logged at info), status refused by the status pipeline (warn with the Failure), restore_resource below one whole point.
 */
internal object EntityGone {
    private val log = LoggerFactory.getLogger(EntityGone::class.java)
    private val counts = ConcurrentHashMap<String, AtomicLong>()

    /** Counts the skip of [site] for [entity] (log line at debug; the counter is [count]). */
    fun skipped(site: String, entity: UUID?) {
        counts.computeIfAbsent(site) { AtomicLong() }.incrementAndGet()
        log.debug("skill engine: {} skipped, entity {} is gone", site, entity)
    }

    fun count(site: String): Long = counts[site]?.get() ?: 0L
}
