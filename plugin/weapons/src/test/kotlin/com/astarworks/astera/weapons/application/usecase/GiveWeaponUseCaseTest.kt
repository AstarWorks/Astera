package com.astarworks.astera.weapons.application.usecase

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.testfixtures.FakeMessageRenderer
import com.astarworks.astera.testfixtures.FakePlayerGateway
import com.astarworks.astera.weapons.application.port.outbound.DeliveryResult
import com.astarworks.astera.weapons.testfixtures.FakeMcPlayer
import com.astarworks.astera.weapons.testfixtures.FakeMcServer
import com.astarworks.astera.weapons.testfixtures.FakeWeaponDelivery
import com.astarworks.astera.weapons.testfixtures.FakeWeaponRegistry
import com.astarworks.astera.weapons.testfixtures.weaponSpec
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class GiveWeaponUseCaseTest {

    private val spec = weaponSpec()
    private val targetId = PlayerId(UUID.randomUUID())
    private val targetPlayer = FakeMcPlayer(id = targetId, name = "Alice")

    private val weapons = FakeWeaponRegistry().apply { register(spec) }
    private val players = FakePlayerGateway().apply { register("Alice", targetId) }
    private val server = FakeMcServer().apply { register(targetPlayer) }
    private val delivery = FakeWeaponDelivery()
    private val msg = FakeMessageRenderer()

    private val uc = GiveWeaponUseCase(weapons, players, server, delivery, msg)

    @Test
    fun `success path delivers weapon and notifies invoker`() {
        val invokerId = PlayerId(UUID.randomUUID())

        val result = uc.execute(
            GiveWeaponUseCase.Request(invokerId = invokerId, targetName = "Alice", weaponIdStr = "example-sword"),
        )

        assertThat(result.isSuccess).isTrue()
        assertThat(delivery.gave).containsExactly(targetPlayer to spec)
        // The success message is dispatched to the invoker (not the target).
        assertThat(players.messages.map { it.first }).containsExactly(invokerId)
    }

    @Test
    fun `unknown weapon yields WeaponNotFound and skips delivery`() {
        val result = uc.execute(GiveWeaponUseCase.Request(null, "Alice", "missing-weapon"))
        assertThat(result.isFailure).isTrue()
        assertThat(result.errorOrNull()).isEqualTo(GiveWeaponError.WeaponNotFound("missing-weapon"))
        assertThat(delivery.gave).isEmpty()
    }

    @Test
    fun `invalid weapon id (uppercase) yields InvalidWeaponId`() {
        val result = uc.execute(GiveWeaponUseCase.Request(null, "Alice", "Example-Sword"))
        assertThat(result.isFailure).isTrue()
        assertThat(result.errorOrNull()).isEqualTo(GiveWeaponError.InvalidWeaponId("Example-Sword"))
        assertThat(delivery.gave).isEmpty()
    }

    @Test
    fun `missing player yields PlayerNotFound`() {
        val result = uc.execute(GiveWeaponUseCase.Request(null, "Bob", "example-sword"))
        assertThat(result.isFailure).isTrue()
        assertThat(result.errorOrNull()).isEqualTo(GiveWeaponError.PlayerNotFound("Bob"))
        assertThat(delivery.gave).isEmpty()
    }

    @Test
    fun `inventory full delivery surfaces as InventoryFull error`() {
        delivery.nextResult = DeliveryResult.InventoryFull("Alice")

        val result = uc.execute(GiveWeaponUseCase.Request(null, "Alice", "example-sword"))
        assertThat(result.isFailure).isTrue()
        assertThat(result.errorOrNull()).isEqualTo(GiveWeaponError.InventoryFull("Alice"))
        // Delivery was attempted exactly once before the failure was surfaced.
        assertThat(delivery.gave).hasSize(1)
    }

    @Test
    fun `unknown item from delivery surfaces as UnknownItem error`() {
        delivery.nextResult = DeliveryResult.UnknownItem("missing-item")

        val result = uc.execute(GiveWeaponUseCase.Request(null, "Alice", "example-sword"))
        assertThat(result.isFailure).isTrue()
        assertThat(result.errorOrNull()).isEqualTo(GiveWeaponError.UnknownItem("missing-item"))
        assertThat(delivery.gave).hasSize(1)
    }
}
