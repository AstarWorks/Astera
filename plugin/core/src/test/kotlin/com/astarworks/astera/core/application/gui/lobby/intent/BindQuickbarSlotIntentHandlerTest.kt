package com.astarworks.astera.core.application.gui.lobby.intent

import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.port.outbound.IPlayerSettingsRepository
import com.astarworks.astera.core.application.port.outbound.guidepth.GuiModalError
import com.astarworks.astera.core.application.port.outbound.guidepth.IGuiModalGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.guidepth.GuiModalSpec
import com.astarworks.astera.core.domain.guidepth.ModalHandle
import com.astarworks.astera.core.domain.i18n.LocalizedLore
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerSettings
import com.astarworks.astera.testfixtures.FakeMcPlayer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class BindQuickbarSlotIntentHandlerTest {

    private fun pid(): PlayerId = PlayerId(UUID.randomUUID())

    private class RecordingModalGateway : IGuiModalGateway {
        public val shown: MutableList<Pair<PlayerId, GuiModalSpec>> = mutableListOf()

        override fun showModal(playerId: PlayerId, modal: GuiModalSpec): Result<ModalHandle, GuiModalError> {
            shown += playerId to modal
            return Result.Success(ModalHandle(UUID.randomUUID().toString()))
        }

        override fun dismissModal(handle: ModalHandle): Result<Unit, GuiModalError> = Result.Success(Unit)
        override fun dismissAllForPlayer(playerId: PlayerId): Result<Unit, GuiModalError> = Result.Success(Unit)
        override fun activeModals(playerId: PlayerId): List<ModalHandle> = emptyList()
    }

    private class FakeSettings : IPlayerSettingsRepository {
        private val store: MutableMap<PlayerId, PlayerSettings> = mutableMapOf()
        override fun find(playerId: PlayerId): PlayerSettings? = store[playerId]
        override fun upsert(settings: PlayerSettings) { store[settings.playerId] = settings }
    }

    private class RecordingGateway : IPlayerGateway {
        public val sent: MutableList<Pair<PlayerId, String>> = mutableListOf()
        override fun findByName(name: String): PlayerId? = null
        override fun sendMessage(playerId: PlayerId, rendered: String) {
            sent += playerId to rendered
        }
    }

    private class StubMessages : IMessageRenderer {
        override fun render(playerId: PlayerId?, key: MessageKey, placeholders: Map<String, String>): String =
            "${key.value}|${placeholders.entries.joinToString(",") { "${it.key}=${it.value}" }}"

        override fun renderLore(playerId: PlayerId?, key: MessageKey, placeholders: Map<String, String>): List<String> =
            listOf(render(playerId, key, placeholders))

        override fun renderText(playerId: PlayerId?, text: LocalizedText, placeholders: Map<String, String>): String =
            text.toString()

        override fun renderLocalizedLore(playerId: PlayerId?, lore: LocalizedLore, placeholders: Map<String, String>): List<String> =
            emptyList()
    }

    private fun handler(
        modalGateway: IGuiModalGateway = RecordingModalGateway(),
        settings: IPlayerSettingsRepository = FakeSettings(),
        skillIds: List<String> = listOf("fireball", "wind-dash"),
        gateway: IPlayerGateway = RecordingGateway(),
        messages: IMessageRenderer = StubMessages(),
    ): BindQuickbarSlotIntentHandler = BindQuickbarSlotIntentHandler(
        modalGateway = modalGateway,
        settings = settings,
        skillIdsProvider = { skillIds },
        playerGateway = gateway,
        messages = messages,
    )

    @Test
    fun `open intent shows quickbar selector modal`() {
        val modal = RecordingModalGateway()
        val player = FakeMcPlayer(id = pid())
        val h = handler(modalGateway = modal)

        h.handle(GuiIntent("lobby.hotbar.bind.3"), player)

        assertThat(modal.shown).hasSize(1)
        val (target, spec) = modal.shown.single()
        assertThat(target).isEqualTo(player.id)
        assertThat(spec).isInstanceOf(GuiModalSpec.QuickbarSelector::class.java)
        val qb = spec as GuiModalSpec.QuickbarSelector
        assertThat(qb.slots).hasSize(2)
        assertThat(qb.onSelect.key).isEqualTo("lobby.hotbar.bind.commit.3")
    }

    @Test
    fun `commit intent writes the quickCastSlots entry`() {
        val settings = FakeSettings()
        val player = FakeMcPlayer(id = pid())
        val h = handler(settings = settings)

        h.handle(
            GuiIntent(
                key = "lobby.hotbar.bind.commit.5",
                payload = mapOf("skillId" to "fireball"),
            ),
            player,
        )

        val stored = settings.find(player.id)
        assertThat(stored).isNotNull
        assertThat(stored!!.quickCastSlots).containsEntry(5, "fireball")
    }

    @Test
    fun `commit intent without skillId payload is dropped`() {
        val settings = FakeSettings()
        val player = FakeMcPlayer(id = pid())
        val h = handler(settings = settings)

        h.handle(GuiIntent("lobby.hotbar.bind.commit.5"), player)

        assertThat(settings.find(player.id)).isNull()
    }

    @Test
    fun `open intent with invalid slot is dropped`() {
        val modal = RecordingModalGateway()
        val h = handler(modalGateway = modal)
        h.handle(GuiIntent("lobby.hotbar.bind.0"), FakeMcPlayer(id = pid()))
        h.handle(GuiIntent("lobby.hotbar.bind.10"), FakeMcPlayer(id = pid()))
        assertThat(modal.shown).isEmpty()
    }

    @Test
    fun `no skills means warn message and no modal`() {
        val modal = RecordingModalGateway()
        val gateway = RecordingGateway()
        val h = handler(modalGateway = modal, skillIds = emptyList(), gateway = gateway)
        val player = FakeMcPlayer(id = pid())

        h.handle(GuiIntent("lobby.hotbar.bind.1"), player)

        assertThat(modal.shown).isEmpty()
        assertThat(gateway.sent).hasSize(1)
        assertThat(gateway.sent.single().second).contains("astera.hotbar.bind.no-skills")
    }

    @Test
    fun `existing quickCastSlots entries are preserved on commit`() {
        val player = FakeMcPlayer(id = pid())
        val settings = FakeSettings().apply {
            upsert(
                PlayerSettings.default(player.id).copy(quickCastSlots = mapOf(2 to "old-skill")),
            )
        }
        val h = handler(settings = settings)

        h.handle(
            GuiIntent("lobby.hotbar.bind.commit.5", mapOf("skillId" to "fireball")),
            player,
        )

        val stored = settings.find(player.id)!!
        assertThat(stored.quickCastSlots).containsEntry(2, "old-skill")
        assertThat(stored.quickCastSlots).containsEntry(5, "fireball")
    }
}
