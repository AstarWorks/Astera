package com.astarworks.astera.core.application.service.presentation

import com.astarworks.astera.core.application.port.outbound.effect.ISoundCuePlayer
import com.astarworks.astera.core.application.port.outbound.presentation.IPresentation
import com.astarworks.astera.core.application.port.outbound.presentation.IPresentationTable
import com.astarworks.astera.core.application.port.outbound.presentation.IPresentationVisuals
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.ParticleVisibility
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.presentation.PresentationEntry
import com.astarworks.astera.core.domain.world.WorldId
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow

/**
 * Default [IPresentation] (FX-05): table row -> [ISoundCuePlayer] + [IPresentationVisuals]. A particle burst is shown
 * only to viewers whose particle-visibility setting admits the subject's effects (`OwnOnly` = only the subject,
 * `OwnTeamOnly` = subject + same team, `All`).
 */
public class PresentationPlayer(
    private val table: IPresentationTable,
    private val cues: ISoundCuePlayer,
    private val visuals: () -> IPresentationVisuals,
    private val visibilityOf: (PlayerId) -> ParticleVisibility,
    private val sameTeam: (PlayerId, PlayerId) -> Boolean,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : IPresentation {
    private val log = LoggerFactory.getLogger(javaClass)
    private val warned: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val chains: MutableMap<Pair<PlayerId, String>, Chain> = ConcurrentHashMap()

    private class Chain(val lastMillis: Long, val step: Int)

    override fun play(
        moment: String,
        subject: PlayerId,
        args: Map<String, String>,
        at: Vec3?,
        world: WorldId?,
        audience: Collection<PlayerId>,
    ): Boolean {
        val e = table.entry(moment)
        if (e == null) {
            if (warned.add(moment)) log.warn("Presentation moment '{}' has no row in content/presentation/events.yaml", moment)
            return false
        }
        e.cue?.let { cues.play(subject, it, at, world, pitchScale(subject, e)) }
        e.audienceCue?.let { cue -> cues.playAll(audience.filter { it != subject }, cue, at, world) }
        if (e.title != null || e.subtitle != null) visuals().title(subject, e.title, e.subtitle, args, e.titleStayTicks)
        e.actionBar?.let { visuals().actionBar(subject, resolve(it, args), args) }
        val preset = e.particle?.let { id ->
            table.preset(id).also { if (it == null && warned.add("preset:$id")) log.warn("Particle preset '{}' (moment '{}') is not defined", id, moment) }
        }
        if (preset != null) {
            val viewers = (listOf(subject) + audience).distinct().filter { canSee(it, subject) }
            if (viewers.isNotEmpty()) visuals().particles(preset, viewers, subject, at, world)
        }
        return true
    }

    /** Message keys may carry `{name}` placeholders filled from [args] (e.g. `picked.{kind}`). */
    private fun resolve(key: String, args: Map<String, String>): MessageKey =
        MessageKey(args.entries.fold(key) { acc, (k, v) -> acc.replace("{$k}", v) })

    private fun canSee(viewer: PlayerId, source: PlayerId): Boolean = when (visibilityOf(viewer)) {
        ParticleVisibility.All -> true
        ParticleVisibility.OwnOnly -> viewer == source
        ParticleVisibility.OwnTeamOnly -> viewer == source || sameTeam(viewer, source)
    }

    /** Rising pitch for rapid repeats of the same moment by the same player. */
    private fun pitchScale(subject: PlayerId, e: PresentationEntry): Double {
        if (e.chainWindowMillis <= 0L || e.chainMaxSteps <= 0) return 1.0
        val now = nowMillis()
        val next = chains.compute(subject to e.moment) { _, prev ->
            if (prev != null && now - prev.lastMillis <= e.chainWindowMillis) Chain(now, minOf(prev.step + 1, e.chainMaxSteps)) else Chain(now, 0)
        }!!
        return 2.0.pow(e.chainPitchSemitones * next.step / 12.0)
    }
}
