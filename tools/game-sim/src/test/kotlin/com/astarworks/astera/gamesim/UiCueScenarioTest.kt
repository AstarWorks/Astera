package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter
import com.astarworks.astera.core.application.port.outbound.effect.ISoundRegistry
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.effect.SoundId
import com.astarworks.astera.core.domain.gui.GuiIntent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** FX-10: every GUI button click plays the click cue (settings buttons play confirm), through the one cue path. */
class UiCueScenarioTest {

    private fun ScenarioScope.heard(who: SimEntity, cue: String): Int {
        val def = checkNotNull(sim.services.getOrThrow<ISoundRegistry>().find(SoundId(cue))) { "cue $cue missing" }
        return sim.playerSounds.calls.count { it.playerId == who.id && it.sound.key == def.resourceKey && it.sound.volume > 0.0 }
    }

    @Test
    fun `a refused social action plays deny once and a normal click does not`() {
        scenario("ui-deny") {
            val p = player("refused")
            val router = sim.services.getOrThrow<IGuiIntentRouter>()
            val view = checkNotNull(sim.server.view(p.id))

            router.dispatch(view, GuiIntent("gui.open", mapOf("gui" to "main-menu")))
            assertThat(heard(p, "astera:ui/deny")).`as`("plain click is not a refusal").isEqualTo(0)

            // Party invite without a party, friend request to an unknown name: both answer in red.
            router.dispatch(view, GuiIntent("lobby.party.invite", mapOf("target" to "nobody-here")))
            assertThat(heard(p, "astera:ui/deny")).isEqualTo(1)
            router.dispatch(view, GuiIntent("lobby.friends.add", mapOf("target" to "nobody-here")))
            assertThat(heard(p, "astera:ui/deny")).isEqualTo(2)
        }
    }

    @Test
    fun `a menu click plays the click cue and a settings click plays confirm`() {
        scenario("ui-cues") {
            val p = player("clicker")
            val router = sim.services.getOrThrow<IGuiIntentRouter>()
            val view = checkNotNull(sim.server.view(p.id))

            router.dispatch(view, GuiIntent("gui.open", mapOf("gui" to "main-menu")))
            assertThat(heard(p, "astera:ui/click")).isEqualTo(1)
            assertThat(heard(p, "astera:ui/confirm")).isEqualTo(0)

            router.dispatch(view, GuiIntent("settings.toggle", mapOf("key" to "double-jump")))
            assertThat(heard(p, "astera:ui/confirm")).isEqualTo(1)
            assertThat(heard(p, "astera:ui/click")).isEqualTo(1)
        }
    }
}
