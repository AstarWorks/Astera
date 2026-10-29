package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.runtime.CastRequest
import com.astarworks.astera.core.application.skillengine.runtime.player.LiveValueSource
import com.astarworks.astera.core.application.skillengine.runtime.player.RuntimeEvent
import com.astarworks.astera.core.application.skillengine.runtime.player.TablePlayer
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillVisualGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Plays [skill] through the real [TablePlayer] on [rec]'s gateways, with the visual gateway and the live-value source swappable
 * (to observe poses or the per-row scope), and hands the player back so a case can read its `faults()`.
 */
fun playProbe(
    skill: CompiledSkill,
    seed: Long = 1L,
    ticks: Int,
    rec: GatewayRecorder = GatewayRecorder(),
    visual: ISkillVisualGateway = rec.visual,
    live: LiveValueSource = rec.live,
    events: List<RuntimeEvent> = emptyList(),
): TablePlayer {
    val player = TablePlayer(visual, rec.particles, rec.sounds, rec.gameplay, rec.query, live, rec, rec.movement, rec.items, rec.world)
    player.hooks = rec.hooks
    val request = CastRequest(
        skill = skill, caster = rec.caster, world = WorldId("world"), casterFeet = Vec3(0.0, 0.0, 0.0),
        casterYawDeg = 0.0, casterPitchDeg = 0.0, seed = seed, startTick = 0,
        heldItem = skill.def.item?.takeIf { it != "any" },
    )
    val cast = when (val s = player.start(request, skill)) {
        is Result.Success -> s.value
        is Result.Failure -> throw AssertionError("start failed: ${s.error}")
    }
    for (now in 0..ticks) {
        rec.tick = now
        events.filter { it.tick == now }.forEach { player.deliver(cast, it) }
        player.tick(now)
    }
    return player
}
