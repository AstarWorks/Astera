package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.items.application.service.refinement.RefinementProfileRegistry
import com.astarworks.astera.items.application.usecase.refinement.ApplyRefinementUseCase
import com.astarworks.astera.items.domain.refinement.Refinement
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class RefinementContentScenarioTest {
    @TempDir
    lateinit var content: Path

    @Test
    fun `startup loads refinement profiles and skips malformed files`() {
        // Reuse the production content without modifying it; only this category is test-owned.
        Files.list(GameSim.defaultContentDir()).use { entries ->
            entries.filter { it.fileName.toString() != "refinement" }.forEach {
                Files.createSymbolicLink(content.resolve(it.fileName), it)
            }
        }
        val profiles = Files.createDirectory(content.resolve("refinement"))
        Files.writeString(profiles.resolve("sword.yaml"), """
            id: knight-sword
            profile:
              enabled: true
              upgradeable_to: 5
        """.trimIndent())
        Files.writeString(profiles.resolve("broken.yaml"), "id: invalid profile: [")
        val sim = GameSim.boot("refinement-content-bootstrap", contentDir = content)
        try {
            val registry = sim.services.getOrThrow<RefinementProfileRegistry>()
            assertThat(registry.resolve(ItemId("knight-sword")).upgradeableTo).isEqualTo(5)
            val player = sim.spawnPlayer("smith")
            val result = sim.services.getOrThrow<ApplyRefinementUseCase>()
                .apply(player.id, null, ItemId("knight-sword"), Refinement.Star(3))
            assertThat(result.isSuccess).isTrue()
            assertThat(result.getOrNull()!!.instance.layers()).containsExactly(Refinement.Star(3))
        } finally {
            sim.writeTrace()
            sim.shutdown()
        }
    }
}
