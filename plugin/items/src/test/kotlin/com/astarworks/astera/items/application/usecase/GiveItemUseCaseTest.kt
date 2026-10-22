package com.astarworks.astera.items.application.usecase

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.application.port.outbound.item.DeliveryResult
import com.astarworks.astera.items.testfixtures.FakeItemDelivery
import com.astarworks.astera.items.testfixtures.InMemoryItemRegistry
import com.astarworks.astera.items.testfixtures.itemSpec
import com.astarworks.astera.testfixtures.FakeMcPlayer
import com.astarworks.astera.testfixtures.FakeMcServer
import com.astarworks.astera.testfixtures.FakeMessageRenderer
import com.astarworks.astera.testfixtures.FakePlayerGateway
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class GiveItemUseCaseTest {

    private val spec = itemSpec()
    private val targetId = PlayerId(UUID.randomUUID())
    private val targetPlayer = FakeMcPlayer(id = targetId, name = "Alice")

    private val items = InMemoryItemRegistry().apply { register(spec) }
    private val players = FakePlayerGateway().apply { register("Alice", targetId) }
    private val server = FakeMcServer().apply { register(targetPlayer) }
    private val delivery = FakeItemDelivery()
    private val msg = FakeMessageRenderer()

    private val uc = GiveItemUseCase(items, players, server, delivery, msg)

    @Test
    fun `success path delivers item and notifies invoker`() {
        val invokerId = PlayerId(UUID.randomUUID())

        val result = uc.execute(
            GiveItemUseCase.Request(
                invokerId = invokerId,
                targetName = "Alice",
                itemIdStr = "example-sword",
                amount = 1,
            ),
        )

        assertThat(result.isSuccess).isTrue()
        assertThat(delivery.gave).hasSize(1)
        assertThat(delivery.gave[0].target).isEqualTo(targetPlayer)
        assertThat(delivery.gave[0].spec).isEqualTo(spec)
        assertThat(delivery.gave[0].amount).isEqualTo(1)
        assertThat(players.messages.map { it.first }).containsExactly(invokerId)
    }

    @Test
    fun `unknown item yields ItemNotFound and skips delivery`() {
        val result = uc.execute(
            GiveItemUseCase.Request(null, "Alice", "missing-item"),
        )
        assertThat(result.isFailure).isTrue()
        assertThat(result.errorOrNull()).isEqualTo(GiveItemError.ItemNotFound("missing-item"))
        assertThat(delivery.gave).isEmpty()
    }

    @Test
    fun `invalid item id yields InvalidItemId`() {
        val result = uc.execute(
            GiveItemUseCase.Request(null, "Alice", "Example-Sword"),
        )
        assertThat(result.isFailure).isTrue()
        assertThat(result.errorOrNull()).isEqualTo(GiveItemError.InvalidItemId("Example-Sword"))
        assertThat(delivery.gave).isEmpty()
    }

    @Test
    fun `missing player yields PlayerNotFound`() {
        val result = uc.execute(
            GiveItemUseCase.Request(null, "Bob", "example-sword"),
        )
        assertThat(result.isFailure).isTrue()
        assertThat(result.errorOrNull()).isEqualTo(GiveItemError.PlayerNotFound("Bob"))
        assertThat(delivery.gave).isEmpty()
    }

    @Test
    fun `inventory full delivery surfaces as InventoryFull error`() {
        delivery.nextResult = DeliveryResult.InventoryFull("Alice")
        val result = uc.execute(GiveItemUseCase.Request(null, "Alice", "example-sword"))
        assertThat(result.isFailure).isTrue()
        assertThat(result.errorOrNull()).isEqualTo(GiveItemError.InventoryFull("Alice"))
        assertThat(delivery.gave).hasSize(1)
    }

    @Test
    fun `invalid amount is rejected before lookup`() {
        val result = uc.execute(
            GiveItemUseCase.Request(null, "Alice", "example-sword", amount = 0),
        )
        assertThat(result.isFailure).isTrue()
        assertThat(result.errorOrNull()).isEqualTo(GiveItemError.InvalidAmount(0))
        assertThat(delivery.gave).isEmpty()
    }
}
