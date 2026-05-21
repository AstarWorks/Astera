package com.astarworks.astera.core.domain.anticheat

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AnticheatActionDomainTest {

    @Test
    fun `Kick Ban Notify Quiet are all distinct singletons`() {
        val all: Set<AnticheatAction> = setOf(
            AnticheatAction.Kick,
            AnticheatAction.Ban,
            AnticheatAction.Notify,
            AnticheatAction.Quiet,
        )
        assertThat(all).hasSize(4)
    }

    @Test
    fun `CustomFlag carries free-form key and severity`() {
        val flag = AnticheatSuspicion.CustomFlag(key = "reach.violation", severity = 7)
        assertThat(flag.key).isEqualTo("reach.violation")
        assertThat(flag.severity).isEqualTo(7)
    }

    @Test
    fun `Movement suspicion wraps the local validator output`() {
        val susp = AnticheatSuspicion.Movement(
            MovementSuspicion.HighSpeed(measured = 5.0, max = 1.0),
        )
        val inner = susp.details as MovementSuspicion.HighSpeed
        assertThat(inner.measured).isEqualTo(5.0)
        assertThat(inner.max).isEqualTo(1.0)
    }
}
