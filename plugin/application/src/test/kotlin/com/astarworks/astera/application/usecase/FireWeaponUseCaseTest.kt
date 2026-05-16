package com.astarworks.astera.application.usecase

import com.astarworks.astera.domain.event.WeaponFired
import com.astarworks.astera.domain.model.geometry.Vec3
import com.astarworks.astera.domain.model.player.PlayerId
import com.astarworks.astera.domain.model.weapon.WeaponId
import com.astarworks.astera.testfixtures.FakeBroadcaster
import com.astarworks.astera.testfixtures.FakeWeaponRegistry
import com.astarworks.astera.testfixtures.weaponSpec
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class FireWeaponUseCaseTest {

    private val spec = weaponSpec()
    private val playerId = PlayerId(UUID.randomUUID())
    private val origin = Vec3(1.5, 64.0, -2.5)

    private val weapons = FakeWeaponRegistry().apply { register(spec) }
    private val broadcaster = FakeBroadcaster()
    private val uc = FireWeaponUseCase(broadcaster, weapons)

    @Test
    fun `known weapon publishes WeaponFired with the correct payload`() {
        val result = uc.execute(playerId, spec.id, origin)

        assertThat(result.isSuccess).isTrue()
        assertThat(broadcaster.published).hasSize(1)
        val evt = broadcaster.published.single()
        assertThat(evt).isInstanceOf(WeaponFired::class.java)
        evt as WeaponFired
        assertThat(evt.playerId).isEqualTo(playerId)
        assertThat(evt.weaponId).isEqualTo(spec.id)
        assertThat(evt.at).isEqualTo(origin)
    }

    @Test
    fun `unknown weapon does not publish any event`() {
        val missing = WeaponId("missing-weapon")
        val result = uc.execute(playerId, missing, origin)

        assertThat(result.isFailure).isTrue()
        assertThat(result.errorOrNull()).isEqualTo(FireWeaponError.WeaponNotFound(missing))
        assertThat(broadcaster.published).isEmpty()
    }
}
