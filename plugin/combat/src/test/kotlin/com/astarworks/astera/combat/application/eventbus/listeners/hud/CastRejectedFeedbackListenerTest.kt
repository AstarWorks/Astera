package com.astarworks.astera.combat.application.eventbus.listeners.hud

import com.astarworks.astera.combat.domain.event.CastRejectionReason
import com.astarworks.astera.combat.domain.event.SkillCastRejected
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.testfixtures.FakeHudGateway
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class CastRejectedFeedbackListenerTest {

    private val pid = PlayerId(UUID.randomUUID())
    private val hud = FakeHudGateway()
    private val listener = CastRejectedFeedbackListener(hud)

    private fun rejected(reason: CastRejectionReason, tick: Long) =
        SkillCastRejected(pid, SkillId("pistol-shot"), reason, tick)

    /** Every subtitle drawn (first = Shown, later ones replace the handle in place = Updated). */
    private fun keys(): List<String> = hud.operations.mapNotNull {
        when (it) {
            is FakeHudGateway.HudOp.Shown -> it.layout
            is FakeHudGateway.HudOp.Updated -> it.layout
            else -> null
        }
    }.map { ((it as HudLayout.Subtitle).component as HudComponent.Text).messageKey.value }

    @Test
    fun `shows the reason as a subtitle`() {
        listener.handle(rejected(CastRejectionReason.INSUFFICIENT_AMMO, tick = 0))
        assertThat(keys()).containsExactly("astera.combat.cast.insufficient.ammo")
    }

    @Test
    fun `repeats within the gap are dropped`() {
        listener.handle(rejected(CastRejectionReason.INSUFFICIENT_AMMO, tick = 0))
        listener.handle(rejected(CastRejectionReason.INSUFFICIENT_AMMO, tick = 5))
        listener.handle(rejected(CastRejectionReason.RELOADING, tick = 10))
        assertThat(keys()).containsExactly("astera.combat.cast.insufficient.ammo", "astera.combat.cast.reloading")
    }
}
