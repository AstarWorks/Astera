package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.entity.IEntitySpawner
import com.astarworks.astera.core.application.port.outbound.entity.INpcInteractionRouter
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.Result
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files

/** UI reload gap: `astera admin reload lobby-npcs` diffs by NPC id (despawn removed/changed, spawn new/changed, keep the rest). */
class LobbyNpcReloadScenarioTest {

    @Test
    fun `reload spawns a new npc, respawns a moved one, despawns a removed one and keeps the untouched`() {
        val content = ReloadScenarioSupport.scratchContent("lobby-npcs")
        val dir = content.resolve("lobby-npcs")
        ReloadScenarioSupport.withSim("lobby-npc-reload", content) { sim ->
            val spawner = sim.services.getOrThrow<IEntitySpawner>()
            val before = spawner.all().map { it.id }.toSet()
            assertThat(before).`as`("lobby NPCs spawned at boot").isNotEmpty()
            // A player has clicked every bound NPC; a despawn must forget (and close the GUI of) the removed ones.
            val router = sim.services.getOrThrow<INpcInteractionRouter>()
            val clicker = PlayerId(java.util.UUID.randomUUID())
            val bound = router.all().keys.map { it.first }.toSet()
            bound.forEach { router.recordUse(clicker, it) }

            // Move play-npc (respawn), delete travel-npc (despawn), add extra-npc (spawn); market/social untouched.
            val play = Files.readString(dir.resolve("play-npc.yaml"))
            val moved = play.replace(Regex("(?m)^(\\s+x:\\s*)(-?[0-9.]+)")) { "${it.groupValues[1]}${it.groupValues[2].toDouble() + 1.0}" }
            assertThat(moved).isNotEqualTo(play)
            Files.writeString(dir.resolve("play-npc.yaml"), moved)
            Files.delete(dir.resolve("travel-npc.yaml"))
            Files.writeString(
                dir.resolve("extra-npc.yaml"),
                Files.readString(dir.resolve("market-npc.yaml")).replace("id: market-npc", "id: extra-npc"),
            )

            val report = (ReloadScenarioSupport.reload(sim, "lobby-npcs") as Result.Success).value
            assertThat(report.failed).isZero()
            assertThat(report.notes.single()).contains("+1 spawned").contains("-1 despawned").contains("~1 respawned").contains("2 kept")
            val after = spawner.all().map { it.id }.toSet()
            assertThat(after.size).isEqualTo(before.size) // +1 new, -1 removed, respawn is 1:1
            assertThat(after.intersect(before)).`as`("the two untouched NPCs keep their handle").hasSize(before.size - 2)
            val stillRemembered = bound.filter { router.usersOf(it).isNotEmpty() }
            assertThat(stillRemembered).`as`("only NPCs that were not despawned keep their recorded users").hasSize(before.size - 2)
        }
    }

    @Test
    fun `a bad npc file changes nothing and reports an error`() {
        val content = ReloadScenarioSupport.scratchContent("lobby-npcs")
        val dir = content.resolve("lobby-npcs")
        ReloadScenarioSupport.withSim("lobby-npc-reload-bad", content) { sim ->
            val spawner = sim.services.getOrThrow<IEntitySpawner>()
            val before = spawner.all().map { it.id }.toSet()
            Files.delete(dir.resolve("travel-npc.yaml"))
            Files.writeString(dir.resolve("play-npc.yaml"), Files.readString(dir.resolve("play-npc.yaml")).replace("type: display_entity", "type: nonsense"))
            assertThat(ReloadScenarioSupport.reload(sim, "lobby-npcs")).isInstanceOf(Result.Failure::class.java)
            assertThat(spawner.all().map { it.id }.toSet()).isEqualTo(before)
        }
    }
}
