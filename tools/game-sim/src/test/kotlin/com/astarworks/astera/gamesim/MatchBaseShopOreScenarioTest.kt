package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.application.port.outbound.entity.IEntitySpawner
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import com.astarworks.astera.match.application.port.outbound.IOreNodeRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Every match stage gives each team a base shop NPC (inside its base, near its spawn) and Mid ore to buy tools with:
 * the shipped annihilation-arena (3 teams) and temple-trouble (2 teams) stages spawn one shop NPC per team at match
 * start, remove them at the end, and place their ore-node blocks.
 */
class MatchBaseShopOreScenarioTest {

    private fun check(mode: String, stageId: String, teams: List<String>) {
        scenario("base-shop-$stageId") {
            val spawner = sim.services.getOrThrow<IEntitySpawner>()
            val before = spawner.all().size
            val stage = checkNotNull(sim.services.getOrThrow<IStageRegistry>().find(StageId(stageId)))
            assertThat(stage.shopNpcs.map { it.team.value }).describedAs("%s shop_npcs teams", stageId).containsExactlyInAnyOrderElementsOf(teams)

            // Each NPC stands close to its own team's spawn points (inside the base, not across the map).
            for (npc in stage.shopNpcs) {
                val spawns = stage.spawnPoints.filter { it.team?.value == npc.team.value }
                assertThat(spawns).describedAs("%s spawns of %s", stageId, npc.team.value).isNotEmpty()
                val nearest = spawns.minOf { Math.hypot(it.position.x - (npc.x + stage.origin.x), it.position.z - (npc.z + stage.origin.z)) }
                assertThat(nearest).describedAs("%s shop NPC of %s is %s blocks from its spawn", stageId, npc.team.value, nearest).isLessThan(15.0)
            }

            val players = teams.associate { team -> player("$team-p", team = team) to team }
            val matchId = startMatch(mode, players, stageId)
            assertThat(spawner.all().size).describedAs("one shop NPC per team on %s", stageId).isEqualTo(before + teams.size)

            // Mid ore: the stage's ore-node file loaded and every node block was placed.
            val config = checkNotNull(sim.services.getOrThrow<IOreNodeRegistry>().find(stageId)) { "no content/ore-nodes/$stageId.yaml" }
            val cells = config.nodes.flatMap { it.positions }
            assertThat(cells).isNotEmpty()
            for (p in cells) {
                assertThat(sim.oreNodes.materialAt((p.x + stage.origin.x).toInt(), p.y.toInt(), (p.z + stage.origin.z).toInt()))
                    .describedAs("ore node block at stage (%s %s %s) of %s", p.x, p.y, p.z, stageId).isNotNull()
            }

            sim.services.getOrThrow<IMatchLifecycle>().end(matchId, EndReason.AdminEnded)
            sim.step(10)
            assertThat(spawner.all().size).describedAs("shop NPCs removed with the match").isEqualTo(before)
        }
    }

    @Test
    fun `annihilation-arena gives each of the three teams a base shop NPC and has mid ore`() =
        check("annihilation", "annihilation-arena", listOf("red", "blue", "yellow"))

    @Test
    fun `temple-trouble gives both teams a base shop NPC and has mid ore`() =
        check("capture-wool", "temple-trouble", listOf("red", "blue"))
}
