package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CombatMatchBugHuntScenarioTest {
    @Test
    fun `players can enter a second match after the first ends`() {
        scenario("bug-hunt-second-match") {
            val red = player("red")
            val blue = player("blue")
            val lifecycle = sim.services.getOrThrow<IMatchLifecycle>()
            val matches = sim.services.getOrThrow<IMatchRepository>()
            val first = startMatch("norma", mapOf(red to "red", blue to "blue"))
            lifecycle.end(first, EndReason.AdminEnded)
            assertThat(matches.findMatchOf(red.id)).isNull()
            val second = startMatch("norma", mapOf(red to "blue", blue to "red"))
            assertThat(matches.findMatchOf(red.id)?.id).isEqualTo(second)
            assertThat(matches.findMatchOf(blue.id)?.id).isEqualTo(second)
        }
    }
}
