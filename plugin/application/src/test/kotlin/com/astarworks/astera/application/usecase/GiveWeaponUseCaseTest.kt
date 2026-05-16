package com.astarworks.astera.application.usecase

import com.astarworks.astera.domain.model.player.PlayerId
import com.astarworks.astera.testfixtures.FakeMessageRenderer
import com.astarworks.astera.testfixtures.FakePlayerGateway
import com.astarworks.astera.testfixtures.FakeWeaponRegistry
import com.astarworks.astera.testfixtures.weaponSpec
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class GiveWeaponUseCaseTest {

    private val spec = weaponSpec()
    private val targetId = PlayerId(UUID.randomUUID())

    private val weapons = FakeWeaponRegistry().apply { register(spec) }
    private val players = FakePlayerGateway().apply { register("Alice", targetId) }
    private val msg = FakeMessageRenderer()

    private val uc = GiveWeaponUseCase(weapons, players, msg)

    @Test
    fun `success path gives weapon and notifies invoker`() {
        val invokerId = PlayerId(UUID.randomUUID())

        val result = uc.execute(
            GiveWeaponUseCase.Request(invokerId = invokerId, targetName = "Alice", weaponIdStr = "example-sword")
        )

        assertThat(result.isSuccess).isTrue()
        assertThat(players.given).containsExactly(targetId to spec)
        assertThat(players.messages.map { it.first }).containsExactly(invokerId)
    }

    @Test
    fun `unknown weapon yields WeaponNotFound`() {
        val result = uc.execute(GiveWeaponUseCase.Request(null, "Alice", "missing-weapon"))
        assertThat(result.isFailure).isTrue()
        assertThat(result.errorOrNull()).isEqualTo(GiveWeaponError.WeaponNotFound("missing-weapon"))
        assertThat(players.given).isEmpty()
    }

    @Test
    fun `invalid weapon id (uppercase) yields InvalidWeaponId`() {
        val result = uc.execute(GiveWeaponUseCase.Request(null, "Alice", "Example-Sword"))
        assertThat(result.isFailure).isTrue()
        assertThat(result.errorOrNull()).isEqualTo(GiveWeaponError.InvalidWeaponId("Example-Sword"))
    }

    @Test
    fun `missing player yields PlayerNotFound`() {
        val result = uc.execute(GiveWeaponUseCase.Request(null, "Bob", "example-sword"))
        assertThat(result.isFailure).isTrue()
        assertThat(result.errorOrNull()).isEqualTo(GiveWeaponError.PlayerNotFound("Bob"))
    }
}
