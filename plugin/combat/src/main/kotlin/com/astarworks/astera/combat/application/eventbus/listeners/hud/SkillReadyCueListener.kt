package com.astarworks.astera.combat.application.eventbus.listeners.hud

import com.astarworks.astera.combat.application.port.outbound.ISkillRegistry
import com.astarworks.astera.combat.domain.event.CooldownExpired
import com.astarworks.astera.core.application.port.outbound.effect.ISoundCuePlayer
import com.astarworks.astera.core.domain.cooldown.CooldownKey
import com.astarworks.astera.core.domain.effect.SoundCues
import com.astarworks.astera.core.domain.event.SkillCooldownStarted
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.SkillId

/**
 * A quiet "ready again" cue when a skill comes off cooldown (ADR-0197).
 *
 * Default on for cooldowns of [AUTO_THRESHOLD_TICKS] (3 s) or more; a skill's
 * `ready_cue` overrides that either way. Short cooldowns (a gun's fire rate)
 * would chime constantly, hence the threshold.
 */
public class SkillReadyCueListener(
    private val sounds: ISoundCuePlayer,
    private val skills: ISkillRegistry,
) {

    /** Length of the cooldown that is currently running, per (player, skill). */
    private val running: MutableMap<Pair<PlayerId, SkillId>, Int> = HashMap()

    public fun onStarted(event: SkillCooldownStarted) {
        if (event.durationTicks > 0) running[event.playerId to event.skillId] = event.durationTicks
    }

    public fun onExpired(event: CooldownExpired) {
        val key = event.cooldownKey as? CooldownKey.Skill ?: return
        val skillId = SkillId(key.skillId)
        val duration = running.remove(event.playerId to skillId) ?: return
        val wanted = skills.find(skillId)?.readyCue ?: (duration >= AUTO_THRESHOLD_TICKS)
        if (wanted) sounds.play(event.playerId, SoundCues.COMBAT_SKILL_READY)
    }

    public companion object {
        public const val AUTO_THRESHOLD_TICKS: Int = 60
    }
}
