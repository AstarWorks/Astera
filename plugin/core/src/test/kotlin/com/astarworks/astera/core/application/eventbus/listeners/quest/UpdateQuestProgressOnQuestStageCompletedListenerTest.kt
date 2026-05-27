package com.astarworks.astera.core.application.eventbus.listeners.quest

import com.astarworks.astera.core.domain.event.QuestStageCompleted
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.quest.QuestChainId
import com.astarworks.astera.core.domain.quest.QuestStageId
import com.astarworks.astera.testfixtures.FakeHudGateway
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class UpdateQuestProgressOnQuestStageCompletedListenerTest {

    private val playerId = PlayerId(UUID.randomUUID())
    private val chainId = QuestChainId("test-chain")

    @Test
    fun `intermediate stage advance pushes ActionBar with stage-completed key`() {
        val hud = FakeHudGateway()
        val listener = UpdateQuestProgressOnQuestStageCompletedListener(hud)

        listener.handle(
            QuestStageCompleted(
                playerId = playerId,
                chainId = chainId,
                completedStageId = QuestStageId("stage-1"),
                nextStageId = QuestStageId("stage-2"),
                atTick = 100L,
            ),
        )

        val shown = hud.operations.filterIsInstance<FakeHudGateway.HudOp.Shown>()
        assertThat(shown).hasSize(1)
        val layout = shown.first().layout
        assertThat(layout).isInstanceOf(HudLayout.ActionBar::class.java)
        val text = (layout as HudLayout.ActionBar).component as HudComponent.Text
        assertThat(text.messageKey).isEqualTo(MessageKey("astera.quest.progress.stage-completed"))
    }

    @Test
    fun `terminal stage clear pushes ActionBar with chain-completed key`() {
        val hud = FakeHudGateway()
        val listener = UpdateQuestProgressOnQuestStageCompletedListener(hud)

        listener.handle(
            QuestStageCompleted(
                playerId = playerId,
                chainId = chainId,
                completedStageId = QuestStageId("stage-final"),
                nextStageId = null,
                atTick = 200L,
            ),
        )

        val shown = hud.operations.filterIsInstance<FakeHudGateway.HudOp.Shown>()
        assertThat(shown).hasSize(1)
        val text = (shown.first().layout as HudLayout.ActionBar).component as HudComponent.Text
        assertThat(text.messageKey).isEqualTo(MessageKey("astera.quest.progress.chain-completed"))
    }

    @Test
    fun `handle is per-player — different players get distinct HUD invocations`() {
        val hud = FakeHudGateway()
        val listener = UpdateQuestProgressOnQuestStageCompletedListener(hud)
        val other = PlayerId(UUID.randomUUID())

        listener.handle(
            QuestStageCompleted(playerId, chainId, QuestStageId("s1"), QuestStageId("s2"), 1L),
        )
        listener.handle(
            QuestStageCompleted(other, chainId, QuestStageId("s1"), QuestStageId("s2"), 2L),
        )

        val shown = hud.operations.filterIsInstance<FakeHudGateway.HudOp.Shown>()
        assertThat(shown).hasSize(2)
        assertThat(shown.map { it.playerId }).containsExactly(playerId, other)
    }
}
