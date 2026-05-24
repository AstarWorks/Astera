package com.astarworks.astera.combat.domain.combo

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.SkillId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class ComboMatcherTest {

    private val player = PlayerId(UUID.randomUUID())
    private val sword = ItemId("example-sword")
    private val staff = ItemId("example-staff")

    private fun state(weapon: ItemId, vararg steps: ComboStep): ComboState {
        val ordered = steps.sortedBy { it.atTick }
        return ComboState(
            playerId = player,
            weaponId = weapon,
            steps = ordered,
            lastStepAtTick = ordered.last().atTick,
        )
    }

    @Test
    fun `HitCount matches when streak reaches target`() {
        val s = state(
            sword,
            ComboStep.Hit(player, 1.0, atTick = 0),
            ComboStep.Hit(player, 1.0, atTick = 5),
            ComboStep.Hit(player, 1.0, atTick = 10),
        )
        val route = ComboRoute(
            id = ComboRouteId("test"),
            weaponId = sword,
            pattern = ComboPattern.HitCount(3),
            finisher = SkillId("finisher"),
        )
        assertThat(ComboMatcher.matches(s, route)).isTrue()
    }

    @Test
    fun `HitCount fails when wrong weapon`() {
        val s = state(staff, ComboStep.Hit(player, 1.0, atTick = 0))
        val route = ComboRoute(
            id = ComboRouteId("test"),
            weaponId = sword,
            pattern = ComboPattern.HitCount(1),
            finisher = SkillId("finisher"),
        )
        assertThat(ComboMatcher.matches(s, route)).isFalse()
    }

    @Test
    fun `SkillSequence matches on exact tail`() {
        val s = state(
            sword,
            ComboStep.Hit(player, 1.0, atTick = 0),
            ComboStep.SkillCast(SkillId("a"), atTick = 5),
            ComboStep.SkillCast(SkillId("b"), atTick = 10),
            ComboStep.SkillCast(SkillId("c"), atTick = 15),
        )
        val route = ComboRoute(
            id = ComboRouteId("test"),
            weaponId = sword,
            pattern = ComboPattern.SkillSequence(listOf(SkillId("a"), SkillId("b"), SkillId("c"))),
            finisher = SkillId("finisher"),
        )
        assertThat(ComboMatcher.matches(s, route)).isTrue()
    }

    @Test
    fun `SkillSequence fails when tail order differs`() {
        val s = state(
            sword,
            ComboStep.SkillCast(SkillId("a"), atTick = 0),
            ComboStep.SkillCast(SkillId("c"), atTick = 5),
            ComboStep.SkillCast(SkillId("b"), atTick = 10),
        )
        val route = ComboRoute(
            id = ComboRouteId("test"),
            weaponId = sword,
            pattern = ComboPattern.SkillSequence(listOf(SkillId("a"), SkillId("b"), SkillId("c"))),
            finisher = SkillId("finisher"),
        )
        assertThat(ComboMatcher.matches(s, route)).isFalse()
    }

    @Test
    fun `TimingWindow matches when hits fall within window`() {
        val s = state(
            sword,
            ComboStep.Hit(player, 1.0, atTick = 0),
            ComboStep.Hit(player, 1.0, atTick = 5),
            ComboStep.Hit(player, 1.0, atTick = 10),
        )
        val route = ComboRoute(
            id = ComboRouteId("test"),
            weaponId = sword,
            pattern = ComboPattern.TimingWindow(hits = 3, windowTicks = 20),
            finisher = SkillId("finisher"),
        )
        assertThat(ComboMatcher.matches(s, route)).isTrue()
    }

    @Test
    fun `TimingWindow fails when span exceeds window`() {
        val s = state(
            sword,
            ComboStep.Hit(player, 1.0, atTick = 0),
            ComboStep.Hit(player, 1.0, atTick = 30),
            ComboStep.Hit(player, 1.0, atTick = 60),
        )
        val route = ComboRoute(
            id = ComboRouteId("test"),
            weaponId = sword,
            pattern = ComboPattern.TimingWindow(hits = 3, windowTicks = 20),
            finisher = SkillId("finisher"),
        )
        assertThat(ComboMatcher.matches(s, route)).isFalse()
    }

    @Test
    fun `ComboRoute rejects bonus multiplier above cap`() {
        assertThat(runCatching {
            ComboRoute(
                id = ComboRouteId("over"),
                weaponId = sword,
                pattern = ComboPattern.HitCount(1),
                finisher = SkillId("finisher"),
                bonusDamageMultiplier = 10.0,
            )
        }.isFailure).isTrue()
    }
}
