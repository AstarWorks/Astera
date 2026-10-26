package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.effect.ISoundRegistry
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.effect.SoundId
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.TeamId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * MODE-05: carry-mini-tnt is played on the real `siege-isles-mini-tnt` stage and `content/game-modes/carry-mini-tnt.yaml`
 * (RTM concept, ADR-0029): pieces from the wool-a/b/c spots go into the team pool at the team's own base; at 10 they combine
 * into one TNT at tnt-spawn, which only that team can pick up and which wins when delivered to the enemy base.
 * Stage-local coordinates.
 */
class CarryMiniTntScenarioTest {

    private val pickupSpots = listOf(
        Vec3(0.0, 65.0, -95.0), // wool-a
        Vec3(0.0, 65.0, 8.0), // wool-b
        Vec3(0.0, 65.0, 95.0), // wool-c
    )
    private val tntSpawn = Vec3(0.0, 65.0, -8.0)
    private val blueBase = Vec3(163.0, 65.0, 0.0)
    private val redBase = Vec3(-163.0, 65.0, 0.0)
    private val nowhere = Vec3(0.0, 65.0, -50.0)

    private fun ScenarioScope.markers(): List<String?> = pickupSpots.map { blockAt(it.copy(y = 64.0)) }

    private fun ScenarioScope.tntMarker(): String? = blockAt(tntSpawn.copy(y = 64.0))

    private fun ScenarioScope.heard(who: SimEntity, cue: String): Int {
        val def = checkNotNull(sim.services.getOrThrow<ISoundRegistry>().find(SoundId(cue))) { "cue $cue missing" }
        return sim.playerSounds.calls.count { it.playerId == who.id && it.sound.key == def.resourceKey && it.sound.volume > 0.0 }
    }

    /** Takes all three pieces, shows the enemy base takes none of them, banks them at [home] and waits for the spots to regrow. */
    private fun ScenarioScope.collectAndBank(runner: SimEntity, enemyBase: Vec3, home: Vec3) {
        for (spot in pickupSpots) runner.moveTo(spot)
        assertThat(markers()).`as`("taken pieces leave the spots").doesNotContain("minecraft:tnt")
        runner.moveTo(enemyBase)
        runner.moveTo(home)
        runner.moveTo(nowhere)
        advance(20 * 17)
        assertThat(markers()).`as`("pieces regrew").containsOnly("minecraft:tnt")
    }

    @Test
    fun `carry mini tnt - 10 pooled minis combine into one TNT that is delivered to win`() {
        scenario("siege-carry-mini-tnt") {
            val runner = player("runner", at = nowhere)
            val guard = player("guard", at = nowhere)
            val matchId = startMatch("carry-mini-tnt", mapOf(runner to "red", guard to "blue"))
            assertThat(markers()).`as`("a mini-TNT lies on every spot").containsOnly("minecraft:tnt")
            assertThat(tntMarker()).`as`("no combined TNT yet").isNotEqualTo("minecraft:tnt")

            // Entering a base with empty hands pools nothing.
            guard.moveTo(redBase)
            guard.moveTo(nowhere)
            assertThat(eventsOf<MatchEnded>()).isEmpty()

            // Trips 1-3: the pool grows 3, 6, 9. Nothing combines below 10, and the enemy base never takes pieces.
            repeat(3) { collectAndBank(runner, blueBase, redBase) }
            assertThat(tntMarker()).`as`("9/10 pooled: still no TNT").isNotEqualTo("minecraft:tnt")
            assertThat(heard(runner, "astera:norma/level-up")).`as`("no combine cue yet").isZero()

            // Trip 4 takes the pool to 12: ten combine into one TNT (2 stay pooled), announced through the presentation cue.
            for (spot in pickupSpots) runner.moveTo(spot)
            runner.moveTo(redBase)
            assertThat(tntMarker()).`as`("the combined TNT appears at tnt-spawn").isEqualTo("minecraft:tnt")
            assertThat(heard(runner, "astera:norma/level-up")).`as`("the owning team gets the combine cue").isEqualTo(1)
            assertThat(eventsOf<MatchEnded>()).isEmpty()

            // The enemy cannot take a TNT that is not theirs.
            guard.moveTo(tntSpawn)
            guard.moveTo(nowhere)
            assertThat(tntMarker()).`as`("the enemy did not pick it up").isEqualTo("minecraft:tnt")

            // Our own base is not a delivery; the enemy base is.
            runner.moveTo(tntSpawn)
            assertThat(tntMarker()).`as`("picked up").isNotEqualTo("minecraft:tnt")
            runner.moveTo(redBase)
            assertThat(eventsOf<MatchEnded>()).isEmpty()
            runner.moveTo(blueBase)
            val end = eventsOf<MatchEnded>().single()
            assertThat(end.matchId).isEqualTo(matchId)
            assertThat(end.winningTeam).isEqualTo(TeamId("red"))
            assertThat(markers()).`as`("markers removed").doesNotContain("minecraft:tnt")
            assertThat(tntMarker()).isNotEqualTo("minecraft:tnt")
        }
    }
}
