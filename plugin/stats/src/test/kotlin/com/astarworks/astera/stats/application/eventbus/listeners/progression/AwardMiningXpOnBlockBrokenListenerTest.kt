package com.astarworks.astera.stats.application.eventbus.listeners.progression

import com.astarworks.astera.core.domain.event.BlockBroken
import com.astarworks.astera.core.domain.event.BlockBrokenBySkill
import com.astarworks.astera.core.domain.event.SkillXpGained
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.progression.SkillKind
import com.astarworks.astera.core.domain.progression.SkillXpCurve
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.stats.application.service.progression.InMemorySkillProgressionRepository
import com.astarworks.astera.stats.application.service.progression.InMemorySkillXpCurveRegistry
import com.astarworks.astera.stats.application.usecase.progression.AwardSkillXpUseCase
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class AwardMiningXpOnBlockBrokenListenerTest {

    private val playerId = PlayerId(UUID.randomUUID())
    private lateinit var repo: InMemorySkillProgressionRepository
    private lateinit var bus: FakeDomainEventBus
    private lateinit var useCase: AwardSkillXpUseCase
    private lateinit var listener: AwardMiningXpOnBlockBrokenListener

    @BeforeEach
    fun setUp() {
        repo = InMemorySkillProgressionRepository()
        val curves = InMemorySkillXpCurveRegistry(defaultCurve = SkillXpCurve.exponential())
        bus = FakeDomainEventBus()
        useCase = AwardSkillXpUseCase(repo, curves, bus)
        listener = AwardMiningXpOnBlockBrokenListener(useCase, xpPerBlock = 10L)
    }

    @Test
    fun `BlockBroken from pickaxe awards Mining XP`() {
        val event = BlockBroken(
            matchId = MatchId(UUID.randomUUID().toString()),
            playerId = playerId,
            position = Vec3(0.0, 0.0, 0.0),
            blockId = "minecraft:stone",
            atTick = 0L,
        )
        listener.onPickaxeBreak(event)

        val gained = bus.publishedOfType<SkillXpGained>()
        assertThat(gained).hasSize(1)
        assertThat(gained.first().kind).isEqualTo(SkillKind.Mining)
        assertThat(gained.first().amount).isEqualTo(10L)
        assertThat(gained.first().playerId).isEqualTo(playerId)
    }

    @Test
    fun `BlockBrokenBySkill awards Mining XP to attackerId`() {
        val event = BlockBrokenBySkill(
            world = WorldId("world"),
            position = Vec3(0.0, 0.0, 0.0),
            skillId = SkillId("mine-blast"),
            attackerId = playerId,
            blockType = BlockType(key = "astera:core_block"),
            drops = listOf(ItemId("core-block")),
            atTick = 0L,
        )
        listener.onSkillBreak(event)

        val gained = bus.publishedOfType<SkillXpGained>()
        assertThat(gained).hasSize(1)
        assertThat(gained.first().kind).isEqualTo(SkillKind.Mining)
        assertThat(gained.first().playerId).isEqualTo(playerId)
    }
}
