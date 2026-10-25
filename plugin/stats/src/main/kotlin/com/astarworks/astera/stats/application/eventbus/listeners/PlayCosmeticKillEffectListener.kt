package com.astarworks.astera.stats.application.eventbus.listeners

import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.port.outbound.cosmetic.ICosmeticEffectPlayer
import com.astarworks.astera.core.application.service.cosmetic.CosmeticService
import com.astarworks.astera.core.domain.cosmetic.CosmeticKind

/** ECO-02: the killer's equipped kill-effect cosmetic bursts at the victim. Purely visual. */
public class PlayCosmeticKillEffectListener(
    private val cosmetics: () -> CosmeticService?,
    private val effects: () -> ICosmeticEffectPlayer?,
) {
    public fun handle(event: PlayerKilled) {
        val killer = event.killer ?: return
        val effect = cosmetics()?.equipped(killer, CosmeticKind.KILL_EFFECT) ?: return
        effects()?.playAt(event.victim, effect.value, effect.count)
    }
}
