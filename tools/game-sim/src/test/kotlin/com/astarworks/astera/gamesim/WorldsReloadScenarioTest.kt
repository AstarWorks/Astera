package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.contents.LobbyWorldContents
import com.astarworks.astera.core.application.port.outbound.admin.ILobbyRepaster
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.application.services.register
import com.astarworks.astera.core.domain.Result
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files

/** MAP reload gap: `astera admin reload worlds` applies spawn/rules/marker_version live; world identity changes need a restart (Q6=A). */
class WorldsReloadScenarioTest {

    private class CountingRepaster : ILobbyRepaster {
        var calls = 0
        override fun repaste(): Boolean { calls++; return true }
    }

    @Test
    fun `spawn and rules apply live and a marker_version bump repastes through the repaster`() {
        val content = ReloadScenarioSupport.scratchContent("worlds")
        val file = content.resolve("worlds/lobby.yaml")
        ReloadScenarioSupport.withSim("worlds-reload", content) { sim ->
            val repaster = CountingRepaster()
            sim.services.register<ILobbyRepaster>(repaster, priority = 100)
            val world = sim.services.getOrThrow<LobbyWorldContents>()
            val before = world.current()

            var text = Files.readString(file)
            text = text.replace("  x: 0.5\n  y: 65.0\n  z: 6.5", "  x: 2.5\n  y: 65.0\n  z: 8.5")
                .replace("fall_damage: false", "fall_damage: true")
                .replace(Regex("marker_version: (\\d+)")) { "marker_version: ${it.groupValues[1].toInt() + 1}" }
            Files.writeString(file, text)

            val report = (ReloadScenarioSupport.reload(sim, "worlds") as Result.Success).value
            assertThat(report.failed).isZero()
            assertThat(world.current().spawn.x).isEqualTo(2.5).isNotEqualTo(before.spawn.x)
            assertThat(world.current().rules.fallDamage).isTrue()
            assertThat(repaster.calls).isEqualTo(1)
            assertThat(ReloadScenarioSupport.reload(sim, "worlds")).isInstanceOf(Result.Success::class.java)
            assertThat(repaster.calls).`as`("no second repaste when nothing changed").isEqualTo(1)
        }
    }

    @Test
    fun `a world seed change is refused with a restart-required reason and the world is untouched`() {
        val content = ReloadScenarioSupport.scratchContent("worlds")
        val file = content.resolve("worlds/lobby.yaml")
        ReloadScenarioSupport.withSim("worlds-reload-restart", content) { sim ->
            val world = sim.services.getOrThrow<LobbyWorldContents>()
            val before = world.current()
            Files.writeString(file, Files.readString(file).replace("seed: 20261022", "seed: 1"))
            val result = ReloadScenarioSupport.reload(sim, "worlds")
            val failure = result as Result.Failure
            val loadFailed = failure.error as com.astarworks.astera.core.application.port.outbound.ReloadError.LoadFailed
            assertThat(loadFailed.report.failures.single().message).contains("RESTART_REQUIRED").contains("seed")
            assertThat(world.current()).isEqualTo(before)
        }
    }
}
