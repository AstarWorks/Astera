package com.astarworks.astera.match.application.service.death

import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.damage.ResolvedDamage
import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.service.InMemorySpawnProtectionGateway
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.TeamEliminated
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.item.ItemSpec
import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.i18n.LocalizedLore
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.item.DropPolicy
import com.astarworks.astera.core.domain.item.DurabilityPolicy
import com.astarworks.astera.core.domain.item.EquipSlot
import com.astarworks.astera.core.domain.item.ItemAttributes
import com.astarworks.astera.core.domain.item.ItemTemplate
import com.astarworks.astera.core.domain.item.Rarity
import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.match.Score
import com.astarworks.astera.core.domain.match.Team
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stage.Region
import com.astarworks.astera.core.domain.stage.SpawnPoint
import com.astarworks.astera.core.domain.stage.SpawnRole
import com.astarworks.astera.core.domain.stage.Stage
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.match.application.contents.StageContents
import com.astarworks.astera.match.application.eventbus.listeners.respawn.EliminatedTeamSpectators
import com.astarworks.astera.match.application.service.DefaultSpawnSelector
import com.astarworks.astera.match.application.service.InMemoryMatchRepository
import com.astarworks.astera.match.application.usecase.respawn.RespawnUseCase
import com.astarworks.astera.match.domain.death.DeathRespawnConfig
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakeHudGateway
import com.astarworks.astera.testfixtures.FakeMessageRenderer
import com.astarworks.astera.testfixtures.FakePlayerGateway
import com.astarworks.astera.testfixtures.FakePlayerHealthGateway
import com.astarworks.astera.testfixtures.FakeScheduler
import com.astarworks.astera.testfixtures.FakeWorldGateway
import com.astarworks.astera.testfixtures.spectator.FakeSpectator
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** F3-5: death screen -> death cam -> countdown -> respawn at a team spawn -> spawn protection. */
class DeathRespawnCoordinatorTest {

    private val victim = PlayerId(UUID.randomUUID())
    private val mate = PlayerId(UUID.randomUUID())
    private val killer = PlayerId(UUID.randomUUID())
    private val matchId = MatchId("m-1")
    private val stageId = StageId("arena")
    private val red = TeamId("red")
    private val blue = TeamId("blue")

    private val matches = InMemoryMatchRepository().apply {
        save(
            Match(
                id = matchId,
                phase = MatchPhase.ACTIVE,
                teams = listOf(
                    Team(red, MessageKey("a"), MessageKey("a"), setOf(victim, mate)),
                    Team(blue, MessageKey("a"), MessageKey("a"), setOf(killer)),
                ),
                score = Score(mapOf(red to 0, blue to 0)),
                stageId = stageId,
                startedAtTick = 0L,
            ),
        )
    }
    private val stages = StageContents().apply {
        register(
            stageId,
            Stage(
                id = stageId,
                displayNameKey = MessageKey("a"),
                regions = emptyList<Region>(),
                spawnPoints = listOf(
                    SpawnPoint(world = WorldId("w"), position = Vec3(10.0, 64.0, 20.0), role = SpawnRole.TEAM, team = red),
                    SpawnPoint(world = WorldId("w"), position = Vec3(-10.0, 64.0, -20.0), role = SpawnRole.TEAM, team = blue),
                ),
            ),
        )
    }
    private val scheduler = FakeScheduler()
    private val bus = FakeDomainEventBus()
    private val world = FakeWorldGateway()
    private val health = FakePlayerHealthGateway()
    private val protection = InMemorySpawnProtectionGateway(scheduler, bus)
    private val hud = FakeHudGateway(scheduler = scheduler)
    private val spectator = FakeSpectator().apply { listOf(victim, mate, killer).forEach(::setOnline) }
    private val players = FakePlayerGateway().apply { register("Killer", killer) }
    private val messages = FakeMessageRenderer()
    private val cycler = SpectatorTargetCycler(matches, spectator)
    private val eliminated = EliminatedTeamSpectators(matches, health, spectator, cycler = cycler)

    private fun coordinator(
        config: DeathRespawnConfig = DeathRespawnConfig(respawnDelaySeconds = 5, deathCamSeconds = 2, spawnProtectionSeconds = 3),
        items: IItemRegistry? = null,
    ) = DeathRespawnCoordinator(
        matches = matches,
        stages = stages,
        spawnSelector = DefaultSpawnSelector(matches),
        respawnUseCase = RespawnUseCase(
            healthGateway = health,
            worldGateway = world,
            itemRegistry = EmptyItems,
            protectionGateway = protection,
            eventBus = bus,
            scheduler = scheduler,
        ),
        healthGateway = health,
        protection = protection,
        scheduler = scheduler,
        hud = hud,
        messages = messages,
        players = players,
        items = items,
        spectator = spectator,
        cycler = cycler,
        eliminatedTeams = eliminated,
        configOf = { config },
    )

    private fun kill(source: DamageSource = DamageSource.Item(ItemId("pistol"), killer), by: PlayerId? = killer) =
        PlayerKilled(victim = victim, killer = by, source = source, atTick = scheduler.currentTick())

    private fun advance(seconds: Double) = scheduler.advanceTicks((seconds * 20).toLong())

    @Test
    fun `death spectates the killer then free-cams then respawns at the team spawn after the delay`() {
        val c = coordinator()
        c.onKilled(kill())

        assertThat(c.isDead(victim)).isTrue()
        assertThat(spectator.isSpectating(victim)).isTrue()
        assertThat(spectator.cameraTargetOf(victim)).`as`("death cam on the killer").isEqualTo(killer)
        assertThat(world.teleports).isEmpty()

        advance(1.9)
        assertThat(spectator.cameraTargetOf(victim)).isEqualTo(killer)
        advance(0.2)
        assertThat(spectator.cameraTargetOf(victim)).`as`("free-cam after death_cam_seconds").isNull()
        assertThat(spectator.isSpectating(victim)).isTrue()

        advance(2.8)
        assertThat(world.teleports).`as`("not respawned before the delay").isEmpty()
        advance(0.2)
        assertThat(c.isDead(victim)).isFalse()
        assertThat(spectator.isSpectating(victim)).isFalse()
        assertThat(world.teleports.single().at).isEqualTo(Vec3(10.0, 64.0, 20.0))
        assertThat(health.calls.last()).isInstanceOf(FakePlayerHealthGateway.Call.SetHealth::class.java)
    }

    @Test
    fun `death screen names the killer and weapon and the action bar counts down`() {
        val sword = ItemId("sword")
        val c = coordinator(items = ItemsWithSword(sword))
        c.onKilled(kill(DamageSource.Item(sword, killer)))

        val title = hud.operations.filterIsInstance<FakeHudGateway.HudOp.Shown>()
            .map { it.layout }.filterIsInstance<HudLayout.Subtitle>().single()
        assertThat((title.title as HudComponent.Text).messageKey.value).isEqualTo("astera.death.title")
        val sub = title.component as HudComponent.Text
        assertThat(sub.messageKey.value).isEqualTo("astera.death.subtitle.weapon")
        assertThat(sub.placeholders).containsEntry("killer", "Killer").containsEntry("weapon", "Excalibur")

        advance(5.0)
        val counts = hud.operations.mapNotNull {
            when (it) {
                is FakeHudGateway.HudOp.Shown -> it.layout
                is FakeHudGateway.HudOp.Updated -> it.layout
                else -> null
            }
        }.filterIsInstance<HudLayout.ActionBar>()
            .map { (it.component as HudComponent.Text).placeholders["seconds"] }
        assertThat(counts).containsExactly("5", "4", "3", "2", "1")
    }

    @Test
    fun `environmental death uses the generic subtitle and skips the death cam`() {
        val c = coordinator()
        c.onKilled(kill(DamageSource.Environment.Void, by = null))

        val sub = hud.operations.filterIsInstance<FakeHudGateway.HudOp.Shown>()
            .map { it.layout }.filterIsInstance<HudLayout.Subtitle>().single().component as HudComponent.Text
        assertThat(sub.messageKey.value).isEqualTo("astera.death.subtitle.generic")
        assertThat(spectator.cameraTargetOf(victim)).isNull()
    }

    @Test
    fun `dead players are untouchable and spawn protection starts at the respawn and ends on attack`() {
        val c = coordinator()
        c.onKilled(kill())
        assertThat(protection.isProtected(victim)).`as`("invulnerable while dead").isTrue()

        advance(5.0)
        assertThat(protection.isProtected(victim)).`as`("spawn protection after the respawn").isTrue()
        advance(2.9)
        assertThat(protection.isProtected(victim)).isTrue()
        advance(0.2)
        assertThat(protection.isProtected(victim)).`as`("expires after spawn_protection_seconds").isFalse()
    }

    @Test
    fun `the first attack ends spawn protection`() {
        val c = coordinator()
        c.onKilled(kill())
        advance(5.0)
        assertThat(protection.isProtected(victim)).isTrue()

        c.onDamageDealt(damage(attacker = victim, target = killer))
        assertThat(protection.isProtected(victim)).isFalse()
    }

    @Test
    fun `being hit does not end spawn protection`() {
        val c = coordinator()
        c.onKilled(kill())
        advance(5.0)
        c.onDamageDealt(damage(attacker = killer, target = victim))
        assertThat(protection.isProtected(victim)).isTrue()
    }

    @Test
    fun `zero delay respawns immediately without a death screen`() {
        val c = coordinator(DeathRespawnConfig(respawnDelaySeconds = 0, deathCamSeconds = 2, spawnProtectionSeconds = 3))
        c.onKilled(kill())

        assertThat(world.teleports).hasSize(1)
        assertThat(spectator.isSpectating(victim)).isFalse()
        assertThat(hud.operations).isEmpty()
    }

    @Test
    fun `death cam is clamped to the respawn delay`() {
        val config = DeathRespawnConfig(respawnDelaySeconds = 1, deathCamSeconds = 10, spawnProtectionSeconds = 0)
        assertThat(config.effectiveDeathCamSeconds).isEqualTo(1)
        coordinator(config).onKilled(kill())
        advance(1.0)
        assertThat(world.teleports).hasSize(1)
        assertThat(protection.isProtected(victim)).isFalse()
    }

    @Test
    fun `a second death while dead is ignored`() {
        val c = coordinator()
        c.onKilled(kill())
        c.onKilled(kill())
        advance(5.0)
        assertThat(world.teleports).hasSize(1)
    }

    @Test
    fun `match end cancels the countdown and returns the player to normal play`() {
        val c = coordinator()
        c.onKilled(kill())
        advance(1.0)

        c.onMatchEnded(MatchEnded(matchId = matchId, winningTeam = null, reason = EndReason.AdminEnded, atTick = scheduler.currentTick()))
        advance(10.0)

        assertThat(world.teleports).isEmpty()
        assertThat(spectator.isSpectating(victim)).isFalse()
        assertThat(c.isDead(victim)).isFalse()
    }

    @Test
    fun `an eliminated team keeps spectating and never respawns`() {
        val c = coordinator()
        c.onKilled(kill())
        val event = TeamEliminated(matchId = matchId, teamId = red, survivingPlayers = emptySet(), atTick = 0L)
        eliminated.onTeamEliminated(event)
        c.onTeamEliminated(event)
        advance(10.0)

        assertThat(world.teleports).isEmpty()
        assertThat(spectator.isSpectating(victim)).isTrue()
        assertThat(spectator.isSpectating(mate)).isTrue()
        assertThat(spectator.cameraTargetOf(victim)).`as`("watches a living opponent").isEqualTo(killer)

        // A later death of an eliminated player is taken over, not counted down.
        c.onKilled(kill())
        assertThat(c.isDead(victim)).isFalse()
    }

    @Test
    fun `match end returns eliminated spectators to normal play`() {
        eliminated.onTeamEliminated(TeamEliminated(matchId = matchId, teamId = red, survivingPlayers = emptySet(), atTick = 0L))
        assertThat(spectator.isSpectating(victim)).isTrue()
        eliminated.onMatchEnded(MatchEnded(matchId = matchId, winningTeam = null, reason = EndReason.AdminEnded, atTick = 0L))
        assertThat(spectator.isSpectating(victim)).isFalse()
        assertThat(spectator.isSpectating(mate)).isFalse()
    }

    @Test
    fun `deaths outside a match are ignored`() {
        val outsider = PlayerId(UUID.randomUUID())
        coordinator().onKilled(
            PlayerKilled(outsider, null, DamageSource.Environment.Generic, 0L),
        )
        assertThat(world.teleports).isEmpty()
        assertThat(hud.operations).isEmpty()
    }

    private fun damage(attacker: PlayerId, target: PlayerId) = DamageDealt(
        attacker = attacker,
        target = target,
        resolved = ResolvedDamage(1.0, DamageAttribute.PHYSICAL, wasBlocked = false, wasCritical = false),
        source = DamageSource.Item(ItemId("pistol"), attacker),
        atTick = scheduler.currentTick(),
    )

    private object EmptyItems : IItemRegistry {
        override fun find(key: ItemId): ItemSpec? = null
        override fun all(): Collection<ItemSpec> = emptyList()
    }

    private class ItemsWithSword(private val id: ItemId) : IItemRegistry {
        private val spec = ItemSpec(
            id = id,
            template = ItemTemplate.Vanilla(material = "STONE"),
            displayName = LocalizedText(mapOf(LocaleCode.EN to "Excalibur")),
            lore = LocalizedLore.EMPTY,
            rarity = Rarity.COMMON,
            levelRequirement = 1,
            attributes = ItemAttributes.EMPTY,
            equipSlot = EquipSlot.MAINHAND,
            durability = DurabilityPolicy.DEFAULT,
            dropPolicy = DropPolicy.DEFAULT,
            enchantable = true,
            glint = false,
            skillBindings = emptyList(),
            pdcTags = emptyMap(),
        )
        override fun find(key: ItemId): ItemSpec? = spec.takeIf { key == id }
        override fun all(): Collection<ItemSpec> = listOf(spec)
    }
}
