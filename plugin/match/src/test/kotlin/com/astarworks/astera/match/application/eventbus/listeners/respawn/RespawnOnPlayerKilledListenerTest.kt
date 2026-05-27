package com.astarworks.astera.match.application.eventbus.listeners.respawn

import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.ISpawnProtectionGateway
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.match.Score
import com.astarworks.astera.core.domain.match.Team
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stage.Region
import com.astarworks.astera.core.domain.stage.SpawnFaction
import com.astarworks.astera.core.domain.stage.SpawnPoint
import com.astarworks.astera.core.domain.stage.Stage
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.match.application.contents.StageContents
import com.astarworks.astera.match.application.service.DefaultSpawnSelector
import com.astarworks.astera.match.application.service.InMemoryMatchRepository
import com.astarworks.astera.match.application.usecase.respawn.RespawnUseCase
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakePlayerHealthGateway
import com.astarworks.astera.testfixtures.FakeScheduler
import com.astarworks.astera.testfixtures.FakeWorldGateway
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Wave 5wireup (ADR-0116 §G4) — Respawn-on-killed listener test.
 */
class RespawnOnPlayerKilledListenerTest {

    private val victim = PlayerId(UUID.randomUUID())
    private val matchId = MatchId("test-match-1")
    private val stageId = StageId("test-stage")

    @Test
    fun `handle respawns the victim via RespawnUseCase`() {
        val matches = InMemoryMatchRepository().apply {
            save(
                Match(
                    id = matchId,
                    phase = MatchPhase.ACTIVE,
                    teams = listOf(Team(TeamId("red"), MessageKey("a"), MessageKey("a"), setOf(victim))),
                    score = Score(mapOf(TeamId("red") to 0)),
                    stageId = stageId,
                    startedAtTick = 0L,
                ),
            )
        }
        val stages = StageContents().apply {
            register(
                stageId,
                Stage(
                    id = stageId,
                    displayNameKey = MessageKey("a"),
                    regions = emptyList<Region>(),
                    spawnPoints = listOf(
                        SpawnPoint(
                            world = WorldId("test-world"),
                            position = Vec3(10.0, 64.0, 20.0),
                            faction = SpawnFaction.LOBBY,
                        ),
                    ),
                ),
            )
        }
        val world = FakeWorldGateway()
        val healthGateway = FakePlayerHealthGateway()
        val protection = NoOpProtection()
        val bus = FakeDomainEventBus()
        val scheduler = FakeScheduler()
        val respawnUseCase = RespawnUseCase(
            healthGateway = healthGateway,
            worldGateway = world,
            itemRegistry = NoopItemRegistry,
            protectionGateway = protection,
            eventBus = bus,
            scheduler = scheduler,
        )
        val listener = RespawnOnPlayerKilledListener(
            matches = matches,
            stages = stages,
            spawnSelector = DefaultSpawnSelector(),
            respawnUseCase = respawnUseCase,
        )

        listener.handle(
            PlayerKilled(
                victim = victim,
                killer = null,
                source = DamageSource.Environment.Generic,
                atTick = 1L,
            ),
        )

        // The victim should have been teleported into the configured spawn.
        assertThat(world.teleports).hasSize(1)
        assertThat(world.teleports.first().playerId).isEqualTo(victim)
        // Health restored via the gateway.
        assertThat(healthGateway.calls).isNotEmpty
    }

    @Test
    fun `handle no-ops when victim is not in any active match`() {
        val matches = InMemoryMatchRepository()
        val stages = StageContents()
        val world = FakeWorldGateway()
        val healthGateway = FakePlayerHealthGateway()
        val protection = NoOpProtection()
        val bus = FakeDomainEventBus()
        val scheduler = FakeScheduler()
        val respawnUseCase = RespawnUseCase(
            healthGateway = healthGateway,
            worldGateway = world,
            itemRegistry = NoopItemRegistry,
            protectionGateway = protection,
            eventBus = bus,
            scheduler = scheduler,
        )
        val listener = RespawnOnPlayerKilledListener(matches, stages, DefaultSpawnSelector(), respawnUseCase)

        listener.handle(
            PlayerKilled(
                victim = victim,
                killer = null,
                source = DamageSource.Environment.Generic,
                atTick = 1L,
            ),
        )

        assertThat(world.teleports).isEmpty()
        assertThat(healthGateway.calls).isEmpty()
    }

    private class NoOpProtection : ISpawnProtectionGateway {
        override fun grantProtection(playerId: PlayerId, durationSeconds: Int) {}
        override fun isProtected(playerId: PlayerId): Boolean = false
        override fun revokeProtection(playerId: PlayerId) {}
    }

    private object NoopItemRegistry : IItemRegistry {
        override fun find(key: com.astarworks.astera.core.domain.item.ItemId): com.astarworks.astera.core.domain.item.ItemSpec? = null
        override fun all(): Collection<com.astarworks.astera.core.domain.item.ItemSpec> = emptyList()
    }
}
