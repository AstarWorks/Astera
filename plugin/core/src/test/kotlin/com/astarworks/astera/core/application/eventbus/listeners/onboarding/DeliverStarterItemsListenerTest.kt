package com.astarworks.astera.core.application.eventbus.listeners.onboarding

import com.astarworks.astera.core.application.service.InMemoryPlayerSessionRepository
import com.astarworks.astera.core.domain.event.FirstTimeProfileCreated
import com.astarworks.astera.core.domain.event.PlayerArrivedOnServer
import com.astarworks.astera.core.domain.i18n.LocalizedLore
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.item.DropPolicy
import com.astarworks.astera.core.domain.item.DurabilityPolicy
import com.astarworks.astera.core.domain.item.EquipSlot
import com.astarworks.astera.core.domain.item.ItemAttributes
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.item.ItemSpec
import com.astarworks.astera.core.domain.item.ItemTemplate
import com.astarworks.astera.core.domain.item.Rarity
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerSession
import com.astarworks.astera.core.domain.player.SessionState
import com.astarworks.astera.core.domain.proxy.ServerName
import com.astarworks.astera.testfixtures.FakeItemDelivery
import com.astarworks.astera.testfixtures.FakeMcPlayer
import com.astarworks.astera.testfixtures.FakeMcServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class DeliverStarterItemsListenerTest {

    private val playerId = PlayerId(UUID.randomUUID())
    private val sword = spec("knight-sword")
    private val staff = spec("frost-staff")

    private lateinit var delivery: FakeItemDelivery
    private lateinit var sessions: InMemoryPlayerSessionRepository
    private lateinit var listener: DeliverStarterItemsListener

    @BeforeEach
    fun setUp() {
        val server = FakeMcServer().also { it.register(FakeMcPlayer(id = playerId)) }
        delivery = FakeItemDelivery()
        sessions = InMemoryPlayerSessionRepository()
        listener = DeliverStarterItemsListener({ listOf(sword, staff) }, { delivery }, server, sessions)
    }

    private fun first() = FirstTimeProfileCreated(playerId = playerId, atTick = 1L)

    private fun arrived() = PlayerArrivedOnServer(playerId, ServerName("a"), ServerName("b"))

    @Test
    fun `delivers the starter items immediately when the session is not loading`() {
        listener.handle(first())

        assertThat(delivery.delivered.map { it.itemId.value }).containsExactly("knight-sword", "frost-staff")
    }

    @Test
    fun `waits for the restore to finish while the session is loading`() {
        sessions.upsert(PlayerSession(playerId, SessionState.Loading))

        listener.handle(first())
        assertThat(delivery.delivered).isEmpty()

        listener.handle(arrived())
        assertThat(delivery.delivered).hasSize(2)
    }

    @Test
    fun `an arrival without a first-time grant delivers nothing and the grant is delivered once`() {
        listener.handle(arrived())
        assertThat(delivery.delivered).isEmpty()

        sessions.upsert(PlayerSession(playerId, SessionState.Loading))
        listener.handle(first())
        listener.handle(arrived())
        listener.handle(arrived())
        assertThat(delivery.delivered).hasSize(2)
    }

    @Test
    fun `missing delivery port or offline player does not throw`() {
        val offline = DeliverStarterItemsListener({ listOf(sword) }, { delivery }, FakeMcServer(), sessions)
        offline.handle(first())
        val noPort = DeliverStarterItemsListener(
            { listOf(sword) },
            { null },
            FakeMcServer().also { it.register(FakeMcPlayer(id = playerId)) },
            sessions,
        )
        noPort.handle(first())

        assertThat(delivery.delivered).isEmpty()
    }

    private fun spec(id: String) = ItemSpec(
        id = ItemId(id),
        template = ItemTemplate.Vanilla(material = "IRON_SWORD"),
        displayName = LocalizedText.EMPTY,
        lore = LocalizedLore.EMPTY,
        rarity = Rarity.COMMON,
        levelRequirement = 1,
        attributes = ItemAttributes.EMPTY,
        equipSlot = EquipSlot.MAINHAND,
        durability = DurabilityPolicy.DEFAULT,
        dropPolicy = DropPolicy.DEFAULT,
        enchantable = true,
        glint = false,
        skillBindings = emptyList(),
        pdcTags = emptyMap(),
    )
}
