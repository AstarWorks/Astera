package com.astarworks.astera.combat.adapter.paper.skillengine

import com.astarworks.astera.combat.application.trace.SkillEngineTrace
import com.astarworks.astera.core.application.skillengine.runtime.CastEvent
import com.astarworks.astera.core.application.skillengine.runtime.CastId
import com.astarworks.astera.core.application.skillengine.runtime.CastObserver
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.frame.FrameId
import com.astarworks.astera.core.domain.skillengine.frame.FrameTree
import com.astarworks.astera.core.domain.skillengine.model.SkillEvents
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Cast lifecycle trace for [SkillEngineTrace]: one INFO line per event fired (release, hit, block, reel, ...) with the
 * world position of every frame on the events that matter for aiming (release / hit / block), and one summary line when
 * the cast ends. Does nothing while the trace is off.
 */
internal class SkillEngineTraceObserver : CastObserver {
    private val log = LoggerFactory.getLogger("com.astarworks.astera.combat.skillengine.trace")
    private val casts = ConcurrentHashMap<Long, Summary>()

    private class Summary(val label: String, val firstTick: Int) {
        val events = LinkedHashMap<String, Int>()
        var hits = 0
        var blocks = 0
    }

    /** Called by the runtime when a cast request has started (or failed to). */
    fun started(cast: CastId, label: String, tick: Int) {
        if (!SkillEngineTrace.enabled) return
        casts[cast.value] = Summary(label, tick)
    }

    override fun onTick(cast: CastId, tick: Int, tree: FrameTree, nodes: Set<String>, events: List<CastEvent>) {
        if (!SkillEngineTrace.enabled || events.isEmpty()) return
        val s = casts.getOrPut(cast.value) { Summary("cast ${cast.value}", tick) }
        for (e in events) {
            s.events.merge(e.name, 1, Int::plus)
            if (e.name == SkillEvents.HIT) s.hits++
            if (e.name == SkillEvents.BLOCK) s.blocks++
            val c = e.contact
            val detail = if (c != null) " victim=${c.victim} point=${fmt(c.pointWorld)} normal=${fmt(c.normalWorld)}" else ""
            val frames = if (e.name == SkillEvents.RELEASE || e.name == SkillEvents.HIT || e.name == SkillEvents.BLOCK) framesOf(tree, nodes) else ""
            log.info("skill trace {} +{}t event {} source={}{}{}", s.label, tick - s.firstTick, e.name, e.source ?: "-", detail, frames)
        }
        if (events.any { it.name == SkillEvents.END }) {
            casts.remove(cast.value)
            log.info("skill trace {} ended after {} ticks: hits={} blocks={} events={}", s.label, tick - s.firstTick, s.hits, s.blocks, s.events)
        }
    }

    private fun framesOf(tree: FrameTree, nodes: Set<String>): String =
        nodes.sorted().mapNotNull { n -> tree.pointWorld(FrameId(n), null).getOrNull()?.let { "$n=${fmt(it)}" } }
            .filter { !it.startsWith("enemy") }
            .joinToString(" ", prefix = " frames: ")

    private fun fmt(v: Vec3): String = "(%.2f,%.2f,%.2f)".format(v.x, v.y, v.z)
}
