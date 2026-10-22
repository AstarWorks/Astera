package com.astarworks.astera.stats.application.eventbus.listeners

import com.astarworks.astera.core.domain.achievement.AchievementId
import com.astarworks.astera.core.domain.hud.HudColor
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.stats.application.service.InMemoryAchievementRepository
import com.astarworks.astera.stats.domain.achievement.Achievement
import com.astarworks.astera.stats.domain.event.AchievementUnlocked
import com.astarworks.astera.testfixtures.FakeHudGateway
import com.astarworks.astera.testfixtures.FakeMessageRenderer
import com.astarworks.astera.testfixtures.FakePlayerGateway
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class NotifyOnAchievementUnlockedListenerTest {

    private val playerId = PlayerId(UUID.randomUUID())
    private val now = Instant.parse("2026-05-24T00:00:00Z")

    @Test
    fun `handle sends chat with achievement placeholder`() {
        val player = FakePlayerGateway()
        val renderer = FakeMessageRenderer()
        val hud = FakeHudGateway()
        val achievements = InMemoryAchievementRepository()
        val listener = NotifyOnAchievementUnlockedListener(player, renderer, hud, achievements)

        listener.handle(
            AchievementUnlocked(
                playerId = playerId,
                achievementId = AchievementId("first-mob-kill"),
                unlockedAt = now,
            ),
        )

        assertThat(renderer.calls).hasSize(1)
        val call = renderer.calls.first()
        assertThat(call.playerId).isEqualTo(playerId)
        assertThat(call.key).isEqualTo(MessageKey("astera.achievement.unlocked.chat"))
        assertThat(call.placeholders).containsEntry("achievement", "first-mob-kill")

        assertThat(player.messages).hasSize(1)
        assertThat(player.messages.first().first).isEqualTo(playerId)
    }

    @Test
    fun `handle pushes Subtitle HUD with unlocked-subtitle key`() {
        val player = FakePlayerGateway()
        val renderer = FakeMessageRenderer()
        val hud = FakeHudGateway()
        val achievements = InMemoryAchievementRepository()
        val listener = NotifyOnAchievementUnlockedListener(player, renderer, hud, achievements)

        listener.handle(
            AchievementUnlocked(
                playerId = playerId,
                achievementId = AchievementId("kills-100"),
                unlockedAt = now,
            ),
        )

        val shown = hud.operations.filterIsInstance<FakeHudGateway.HudOp.Shown>()
        assertThat(shown).hasSize(1)
        assertThat(shown.first().playerId).isEqualTo(playerId)
        val layout = shown.first().layout
        assertThat(layout).isInstanceOf(HudLayout.Subtitle::class.java)
        val text = (layout as HudLayout.Subtitle).component as HudComponent.Text
        assertThat(text.messageKey).isEqualTo(MessageKey("astera.achievement.unlocked.subtitle"))
    }

    @Test
    fun `handle is per-player — distinct players receive distinct invocations`() {
        val player = FakePlayerGateway()
        val renderer = FakeMessageRenderer()
        val hud = FakeHudGateway()
        val achievements = InMemoryAchievementRepository()
        val listener = NotifyOnAchievementUnlockedListener(player, renderer, hud, achievements)
        val other = PlayerId(UUID.randomUUID())

        listener.handle(AchievementUnlocked(playerId, AchievementId("a"), now))
        listener.handle(AchievementUnlocked(other, AchievementId("a"), now))

        assertThat(player.messages.map { it.first }).containsExactly(playerId, other)
        val shown = hud.operations.filterIsInstance<FakeHudGateway.HudOp.Shown>()
        assertThat(shown.map { it.playerId }).containsExactly(playerId, other)
    }
    @Test
    fun `handle shows catalog name and description when the achievement is registered`() {
        val player = FakePlayerGateway()
        val renderer = FakeMessageRenderer()
        val hud = FakeHudGateway()
        val achievements = InMemoryAchievementRepository()
        achievements.registerAll(
            listOf(
                Achievement(
                    id = AchievementId("kills-100"),
                    nameKey = MessageKey("astera.stats.achievement.kills-100.name"),
                    descriptionKey = MessageKey("astera.stats.achievement.kills-100.description"),
                    iconItemId = "minecraft:iron_sword",
                ),
            ),
        )
        val listener = NotifyOnAchievementUnlockedListener(player, renderer, hud, achievements)

        listener.handle(AchievementUnlocked(playerId, AchievementId("kills-100"), now))

        val shown = hud.operations.filterIsInstance<FakeHudGateway.HudOp.Shown>()
        val layout = shown.single().layout as HudLayout.Subtitle
        val composite = layout.component as HudComponent.Composite
        assertThat(composite.parts).hasSize(2)
        val name = composite.parts[0] as HudComponent.Text
        val description = composite.parts[1] as HudComponent.Text
        assertThat(name.messageKey).isEqualTo(MessageKey("astera.stats.achievement.kills-100.name"))
        assertThat(name.color).isEqualTo(HudColor.GOLD)
        assertThat(description.messageKey).isEqualTo(MessageKey("astera.stats.achievement.kills-100.description"))
        assertThat(description.color).isEqualTo(HudColor.GRAY)
    }
}
