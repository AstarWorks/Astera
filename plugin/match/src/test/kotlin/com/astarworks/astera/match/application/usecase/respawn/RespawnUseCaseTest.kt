package com.astarworks.astera.match.application.usecase.respawn

import com.astarworks.astera.core.application.contents.Contents
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.service.InMemoryPlayerHealthGateway
import com.astarworks.astera.core.application.service.InMemorySpawnProtectionGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.PlayerProtectionGranted
import com.astarworks.astera.core.domain.event.PlayerRespawned
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.EquipSlot
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.item.ItemSpec
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.stage.SpawnRole
import com.astarworks.astera.core.domain.stage.SpawnPoint
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakeScheduler
import com.astarworks.astera.testfixtures.FakeSpawnProtectionGateway
import com.astarworks.astera.testfixtures.FakeWorldGateway
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Unit tests for [RespawnUseCase] (Wave 5o, ADR-0081).
 *
 * Exercises the happy path + error variants + spawn-protection wiring + kit
 * unknown-item error.
 */
class RespawnUseCaseTest {

    private val playerId = PlayerId(UUID.fromString("00000000-0000-0000-0000-0000000000aa"))
    private val matchId = MatchId("match-test")
    private val world = WorldId("world")
    private val spawn = SpawnPoint(
        world = world,
        position = Vec3(1.0, 64.0, 2.0),
        yaw = 90.0f,
        pitch = 0.0f,
        role = SpawnRole.TEAM,
        team = TeamId("red"),
    )

    private lateinit var worldGateway: FakeWorldGateway
    private lateinit var healthGateway: InMemoryPlayerHealthGateway
    private lateinit var protectionGateway: FakeSpawnProtectionGateway
    private lateinit var itemRegistry: StubItemRegistry
    private lateinit var eventBus: FakeDomainEventBus
    private lateinit var scheduler: FakeScheduler
    private lateinit var useCase: RespawnUseCase

    @BeforeEach
    fun setUp() {
        scheduler = FakeScheduler(startTick = 1000L)
        eventBus = FakeDomainEventBus()
        worldGateway = FakeWorldGateway()
        healthGateway = InMemoryPlayerHealthGateway(
            com.astarworks.astera.core.application.service.InMemoryPlayerAttributeContainer(),
            scheduler,
            eventBus,
        )
        protectionGateway = FakeSpawnProtectionGateway()
        itemRegistry = StubItemRegistry()
        useCase = RespawnUseCase(
            healthGateway = healthGateway,
            worldGateway = worldGateway,
            itemRegistry = itemRegistry,
            protectionGateway = protectionGateway,
            eventBus = eventBus,
            scheduler = scheduler,
            statusEffectContainer = null,
        )
        // Health gateway initialises maxHealth to 20.0 on first touch in
        // InMemoryPlayerHealthGateway; pre-seed so setHealth(20.0) succeeds.
        healthGateway.setMaxHealth(playerId, 20.0)
    }

    @Test
    fun `happy path - teleports + restores health + grants protection + publishes PlayerRespawned`() {
        val result = useCase.respawn(
            RespawnSpec(
                playerId = playerId,
                matchId = matchId,
                spawnPoint = spawn,
                protectionSeconds = 5,
                initialHealth = 20.0,
            ),
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        // Teleport call to spawnPoint with the right world.
        assertThat(worldGateway.teleports).hasSize(1)
        val tp = worldGateway.teleports.single()
        assertThat(tp.playerId).isEqualTo(playerId)
        assertThat(tp.at).isEqualTo(spawn.position)
        assertThat(tp.world).isEqualTo(world)
        // Health restored.
        assertThat(healthGateway.getCurrentHealth(playerId)).isEqualTo(20.0 as Double?)
        // Spawn protection granted with the right duration.
        assertThat(protectionGateway.grants).hasSize(1)
        assertThat(protectionGateway.grants.single().durationSeconds).isEqualTo(5)
        // PlayerRespawned published.
        val events = eventBus.publishedOfType<PlayerRespawned>()
        assertThat(events).hasSize(1)
        assertThat(events.single().playerId).isEqualTo(playerId)
        assertThat(events.single().matchId).isEqualTo(matchId)
        assertThat(events.single().spawnPoint).isEqualTo(spawn)
    }

    @Test
    fun `TeleportFailed when world gateway returns false - no further side effects`() {
        worldGateway.teleportResult = false
        val result = useCase.respawn(
            RespawnSpec(playerId = playerId, matchId = matchId, spawnPoint = spawn),
        )

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        val err = (result as Result.Failure).error
        assertThat(err).isInstanceOf(RespawnError.TeleportFailed::class.java)
        // Health was not touched, protection was not granted, no event fired.
        assertThat(protectionGateway.grants).isEmpty()
        assertThat(eventBus.publishedOfType<PlayerRespawned>()).isEmpty()
    }

    @Test
    fun `zero protectionSeconds skips protection grant but everything else applies`() {
        val result = useCase.respawn(
            RespawnSpec(
                playerId = playerId,
                matchId = matchId,
                spawnPoint = spawn,
                protectionSeconds = 0,
            ),
        )
        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(protectionGateway.grants).isEmpty()
        assertThat(eventBus.publishedOfType<PlayerRespawned>()).hasSize(1)
    }

    @Test
    fun `null matchId is permitted for free-roam respawns`() {
        val result = useCase.respawn(
            RespawnSpec(playerId = playerId, matchId = null, spawnPoint = spawn),
        )
        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(eventBus.publishedOfType<PlayerRespawned>().single().matchId).isNull()
    }

    @Test
    fun `unknown kit item surfaces as UnknownKitItem error`() {
        val ghostId = ItemId("ghost-sword")
        val result = useCase.respawn(
            RespawnSpec(
                playerId = playerId,
                matchId = matchId,
                spawnPoint = spawn,
                kit = PreFillKit(items = listOf(EquipSlot.MAINHAND to ghostId)),
            ),
        )
        assertThat(result).isInstanceOf(Result.Failure::class.java)
        val err = (result as Result.Failure).error
        assertThat(err).isInstanceOf(RespawnError.UnknownKitItem::class.java)
        assertThat((err as RespawnError.UnknownKitItem).itemId).isEqualTo(ghostId)
    }

    @Test
    fun `kit with applyOnRespawn false does not validate items and never fires UnknownKitItem`() {
        val ghostId = ItemId("ghost-bow")
        val result = useCase.respawn(
            RespawnSpec(
                playerId = playerId,
                matchId = matchId,
                spawnPoint = spawn,
                kit = PreFillKit(
                    items = listOf(EquipSlot.MAINHAND to ghostId),
                    applyOnRespawn = false,
                ),
            ),
        )
        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `spawn protection round-trip with InMemorySpawnProtectionGateway publishes Granted`() {
        // Swap the fake for the real gateway so we can verify event wiring.
        val realProtection = InMemorySpawnProtectionGateway(scheduler, eventBus)
        val useCaseReal = RespawnUseCase(
            healthGateway = healthGateway,
            worldGateway = worldGateway,
            itemRegistry = itemRegistry,
            protectionGateway = realProtection,
            eventBus = eventBus,
            scheduler = scheduler,
        )
        val result = useCaseReal.respawn(
            RespawnSpec(playerId = playerId, matchId = matchId, spawnPoint = spawn, protectionSeconds = 5),
        )
        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(realProtection.isProtected(playerId)).isTrue
        assertThat(eventBus.publishedOfType<PlayerProtectionGranted>()).hasSize(1)
    }

    /**
     * Minimal in-test [IItemRegistry] — register no items so any kit reference
     * is "unknown"; tests that need real items add them via [register].
     */
    private class StubItemRegistry : IItemRegistry, Contents<ItemId, ItemSpec> {
        private val map: MutableMap<ItemId, ItemSpec> = mutableMapOf()
        fun register(spec: ItemSpec) { map[spec.id] = spec }
        override fun find(key: ItemId): ItemSpec? = map[key]
        override fun all(): Collection<ItemSpec> = map.values.toList()
    }
}
