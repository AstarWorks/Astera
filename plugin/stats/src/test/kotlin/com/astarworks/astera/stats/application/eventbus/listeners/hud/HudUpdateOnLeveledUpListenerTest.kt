package com.astarworks.astera.stats.application.eventbus.listeners.hud

import com.astarworks.astera.core.domain.effect.SoundSpec
import com.astarworks.astera.core.domain.hud.HudColor
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.stats.domain.event.LeveledUp
import com.astarworks.astera.stats.domain.level.Level
import com.astarworks.astera.stats.domain.level.Xp
import com.astarworks.astera.testfixtures.FakeHudGateway
import com.astarworks.astera.testfixtures.effect.FakePlayerSoundGateway
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class HudUpdateOnLeveledUpListenerTest {

    private val playerId = PlayerId(UUID.randomUUID())

    @Test
    fun `level up shows a Subtitle title with the new level`() {
        val hud = FakeHudGateway()
        val sounds = FakePlayerSoundGateway()
        val listener = HudUpdateOnLeveledUpListener(hud, sounds)

        listener.handle(LeveledUp(playerId, Level(4), Level(5), Xp(1200)))

        val shown = hud.operations.filterIsInstance<FakeHudGateway.HudOp.Shown>()
        assertThat(shown).hasSize(1)
        assertThat(shown.first().playerId).isEqualTo(playerId)
        val layout = shown.first().layout
        assertThat(layout).isInstanceOf(HudLayout.Subtitle::class.java)
        val text = (layout as HudLayout.Subtitle).component as HudComponent.Text
        assertThat(text.messageKey).isEqualTo(MessageKey("astera.hud.leveled-up.title"))
        assertThat(text.placeholders).containsEntry("level", "5")
        assertThat(text.color).isEqualTo(HudColor.YELLOW)
    }

    @Test
    fun `level up plays the vanilla level-up chime for the viewer only`() {
        val hud = FakeHudGateway()
        val sounds = FakePlayerSoundGateway()
        val listener = HudUpdateOnLeveledUpListener(hud, sounds)

        listener.handle(LeveledUp(playerId, Level(1), Level(2), Xp(100)))

        assertThat(sounds.calls).hasSize(1)
        assertThat(sounds.calls.first().playerId).isEqualTo(playerId)
        assertThat(sounds.calls.first().sound).isEqualTo(SoundSpec("minecraft:entity.player.levelup"))
    }
}
