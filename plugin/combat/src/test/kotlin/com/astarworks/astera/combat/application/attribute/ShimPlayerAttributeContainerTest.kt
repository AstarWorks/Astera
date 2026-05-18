package com.astarworks.astera.combat.application.attribute

import com.astarworks.astera.combat.domain.resource.ManaPool
import com.astarworks.astera.core.domain.attribute.AttributeKey
import com.astarworks.astera.core.domain.attribute.AttributeValue
import com.astarworks.astera.core.domain.attribute.Attributes
import com.astarworks.astera.core.domain.attribute.number
import com.astarworks.astera.core.domain.attribute.setNumber
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stats.PlayerStats
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Wave 5b ADR-0058 §6 satisfying-evidence: HUD widgets MUST be able to
 * read HP / mana via `container.number(Attributes.MAX_HP)` etc., today,
 * without waiting for Wave 5e migration.
 */
internal class ShimPlayerAttributeContainerTest {

    private val playerId = PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000001"))

    @Test
    fun `exposes mana current and max from wrapped ManaPool`() {
        val mana = ManaPool.initial(playerId = playerId, max = 80L, regenPerSec = 5.0)
        val shim = ShimPlayerAttributeContainer(stats = PlayerStats.empty(playerId), mana = mana)

        assertThat(shim.number(Attributes.MANA_CURRENT)).isEqualTo(80.0)
        assertThat(shim.number(Attributes.MANA_MAX)).isEqualTo(80.0)
        assertThat(shim.number(Attributes.MANA_REGEN_PER_SEC)).isEqualTo(5.0)
    }

    @Test
    fun `exposes max hp default until Wave 5e introduces real state`() {
        val shim = ShimPlayerAttributeContainer(
            stats = PlayerStats.empty(playerId),
            mana = ManaPool.initial(playerId = playerId, max = 0L, regenPerSec = 0.0),
            maxHpDefault = 25.0,
        )

        assertThat(shim.number(Attributes.MAX_HP)).isEqualTo(25.0)
        assertThat(shim.number(Attributes.CURRENT_HP)).isEqualTo(25.0)
    }

    @Test
    fun `reflects ManaPool spend by rebuilding shim around the new snapshot`() {
        val initial = ManaPool.initial(playerId = playerId, max = 50L, regenPerSec = 1.0)
        val afterSpend = (initial.spend(20L) as com.astarworks.astera.core.domain.Result.Success).value

        val before = ShimPlayerAttributeContainer(PlayerStats.empty(playerId), initial)
        val after = ShimPlayerAttributeContainer(PlayerStats.empty(playerId), afterSpend)

        assertThat(before.number(Attributes.MANA_CURRENT)).isEqualTo(50.0)
        assertThat(after.number(Attributes.MANA_CURRENT)).isEqualTo(30.0)
    }

    @Test
    fun `set overrides shadow the wrapped record reads`() {
        val mana = ManaPool.initial(playerId = playerId, max = 100L, regenPerSec = 1.0)
        val shim = ShimPlayerAttributeContainer(PlayerStats.empty(playerId), mana)

        shim.setNumber(Attributes.MANA_CURRENT, 42.0)
        assertThat(shim.number(Attributes.MANA_CURRENT)).isEqualTo(42.0)

        shim.remove(Attributes.MANA_CURRENT)
        assertThat(shim.number(Attributes.MANA_CURRENT)).isEqualTo(100.0)
    }

    @Test
    fun `unknown key reads as null`() {
        val shim = ShimPlayerAttributeContainer(
            PlayerStats.empty(playerId),
            ManaPool.initial(playerId, 0L, 0.0),
        )
        assertThat(shim.get(AttributeKey("custom.unknown"))).isNull()
    }

    @Test
    fun `keys include wrapped record keys plus ad-hoc overrides`() {
        val shim = ShimPlayerAttributeContainer(
            PlayerStats.empty(playerId),
            ManaPool.initial(playerId, 10L, 0.0),
        )
        shim.set(AttributeKey("custom.extra"), AttributeValue.NumberValue(1.0))

        val keys = shim.keys()
        assertThat(keys).contains(
            Attributes.MAX_HP,
            Attributes.CURRENT_HP,
            Attributes.MANA_CURRENT,
            Attributes.MANA_MAX,
            Attributes.MANA_REGEN_PER_SEC,
            AttributeKey("custom.extra"),
        )
    }

    @Test
    fun `playerId convenience accessor returns wrapped stats playerId`() {
        val shim = ShimPlayerAttributeContainer(
            PlayerStats.empty(playerId),
            ManaPool.initial(playerId, 0L, 0.0),
        )
        assertThat(shim.playerId).isEqualTo(playerId)
    }
}
