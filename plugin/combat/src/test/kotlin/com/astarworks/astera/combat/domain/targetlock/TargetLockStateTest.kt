package com.astarworks.astera.combat.domain.targetlock

import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class TargetLockStateTest {

    private val player = PlayerId(UUID.randomUUID())
    private val bow = ItemId("example-bow")
    private val target = EntityHandle(UUID.randomUUID(), "test")

    @Test
    fun `acquisition build-up has lockedSinceTick null`() {
        val s = TargetLockState(
            playerId = player,
            weaponId = bow,
            target = target,
            aimingTicks = 5,
            lockedSinceTick = null,
            lastAimAtTick = 5,
        )
        assertThat(s.isLocked).isFalse()
    }

    @Test
    fun `locked state survives until lifetime expires`() {
        val s = TargetLockState(
            playerId = player,
            weaponId = bow,
            target = target,
            aimingTicks = 10,
            lockedSinceTick = 100,
            lastAimAtTick = 110,
        )
        val cfg = TargetLockConfig(lockLifetimeTicks = 40)
        assertThat(s.isLocked).isTrue()
        assertThat(s.isLockExpiredAt(cfg, nowTick = 140)).isFalse()
        assertThat(s.isLockExpiredAt(cfg, nowTick = 141)).isTrue()
    }

    @Test
    fun `lockedSinceTick must be at or before lastAimAtTick`() {
        assertThatThrownBy {
            TargetLockState(
                playerId = player,
                weaponId = bow,
                target = target,
                aimingTicks = 10,
                lockedSinceTick = 200,
                lastAimAtTick = 100,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("must be <= lastAimAtTick")
    }

    @Test
    fun `TargetLockConfig caps damageBonusMultiplier`() {
        assertThat(runCatching {
            TargetLockConfig(damageBonusMultiplier = 5.0)
        }.isFailure).isTrue()
    }
}
