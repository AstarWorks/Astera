package com.astarworks.astera.items.adapter.paper

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.adapter.api.event.IMcEvent
import com.astarworks.astera.core.adapter.api.event.IMcEvent.InputKind
import com.astarworks.astera.core.adapter.api.item.IMcItemTemplate
import com.astarworks.astera.core.domain.event.SkillCastRequested
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.item.ItemSkillBinding
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.skill.SkillTrigger
import com.astarworks.astera.items.application.usecase.HandleItemTriggerUseCase
import com.astarworks.astera.items.testfixtures.InMemoryItemRegistry
import com.astarworks.astera.items.testfixtures.itemSpec
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.time.Duration

class ItemSkillTriggerListenerTest {

    private val playerId = PlayerId(UUID.randomUUID())
    private val player = object : IMcPlayer {
        override val id = playerId
        override val name = "tester"
        override val location = Vec3(0.0, 64.0, 0.0)
        override val locale: String? = null
        override fun sendMessage(rendered: String) = Unit
        override fun giveItem(
            template: IMcItemTemplate,
            displayNameOverride: String?,
            loreOverride: List<String>,
        ) = Unit
        override fun heldItemMaterialKey(): String? = null
        override fun sendActionBar(messageKey: MessageKey, placeholders: Map<String, String>) = Unit
        override fun sendTitle(
            titleKey: MessageKey,
            subtitleKey: MessageKey?,
            fadeIn: Duration,
            stay: Duration,
            fadeOut: Duration,
        ) = Unit
    }

    private val scheduler = FakeScheduler()
    private val eventBus = FakeDomainEventBus()
    private val items = InMemoryItemRegistry()
    private val itemId = ItemId("test-weapon")

    private val listener = ItemSkillTriggerListener(
        handleTrigger = HandleItemTriggerUseCase(items, eventBus),
        itemIdResolver = object : IPaperItemIdResolver {
            override fun heldItemId(playerId: PlayerId): ItemId? = null
        },
        nowTick = scheduler::currentTick,
    )

    private fun bind(vararg pairs: Pair<SkillTrigger, String>) {
        items.register(
            itemSpec(
                id = itemId.value,
                skillBindings = pairs.map { (t, s) -> ItemSkillBinding(t, SkillId(s)) },
            ),
        )
    }

    private fun input(kind: InputKind, sneaking: Boolean = false) =
        IMcEvent.PlayerInput(player, kind, sneaking, itemId)

    private fun casts(): List<String> =
        eventBus.publishedOfType<SkillCastRequested>().map { it.skillId.value }

    @Test
    fun `right click fires the right-click skill and reports consumed`() {
        bind(SkillTrigger.RIGHT_CLICK to "shot", SkillTrigger.DROP to "reload")

        assertThat(listener.handle(input(InputKind.RIGHT_CLICK))).isTrue()
        assertThat(casts()).containsExactly("shot")
    }

    @Test
    fun `drop fires reload and is consumed`() {
        bind(SkillTrigger.RIGHT_CLICK to "shot", SkillTrigger.DROP to "reload")

        assertThat(listener.handle(input(InputKind.DROP))).isTrue()
        assertThat(casts()).containsExactly("reload")
    }

    @Test
    fun `unbound input is not consumed so vanilla behaviour stays`() {
        bind(SkillTrigger.RIGHT_CLICK to "shot")

        assertThat(listener.handle(input(InputKind.DROP))).isFalse()
        assertThat(casts()).isEmpty()
    }

    @Test
    fun `sneak right fires only the sneak skill when bound`() {
        bind(SkillTrigger.RIGHT_CLICK to "bolt", SkillTrigger.SNEAK_RIGHT to "nova")

        listener.handle(input(InputKind.RIGHT_CLICK, sneaking = true))

        assertThat(casts()).containsExactly("nova")
    }

    @Test
    fun `sneak right falls back to right click when no sneak binding`() {
        bind(SkillTrigger.RIGHT_CLICK to "shot")

        listener.handle(input(InputKind.RIGHT_CLICK, sneaking = true))

        assertThat(casts()).containsExactly("shot")
    }

    @Test
    fun `left click does not fire a right-click binding (sword bug regression)`() {
        bind(SkillTrigger.LEFT_CLICK to "slash", SkillTrigger.SNEAK_RIGHT to "dash")

        listener.handle(input(InputKind.LEFT_CLICK))

        assertThat(casts()).containsExactly("slash")
    }

    @Test
    fun `repeated right clicks within the hold gap fire HOLD, a late click is a fresh press`() {
        bind(SkillTrigger.RIGHT_CLICK to "press", SkillTrigger.HOLD to "auto")

        listener.handle(input(InputKind.RIGHT_CLICK))
        scheduler.advanceTicks(4)
        listener.handle(input(InputKind.RIGHT_CLICK))
        scheduler.advanceTicks(20)
        listener.handle(input(InputKind.RIGHT_CLICK))

        assertThat(casts()).containsExactly("press", "auto", "press")
    }

    @Test
    fun `held repeats fall back to right click when HOLD is not bound`() {
        bind(SkillTrigger.RIGHT_CLICK to "shot")

        listener.handle(input(InputKind.RIGHT_CLICK))
        scheduler.advanceTicks(4)
        listener.handle(input(InputKind.RIGHT_CLICK))

        assertThat(casts()).containsExactly("shot", "shot")
    }

    @Test
    fun `quit clears hold state`() {
        bind(SkillTrigger.RIGHT_CLICK to "press", SkillTrigger.HOLD to "auto")

        listener.handle(input(InputKind.RIGHT_CLICK))
        listener.handle(IMcEvent.PlayerQuit(player))
        scheduler.advanceTicks(2)
        listener.handle(input(InputKind.RIGHT_CLICK))

        assertThat(casts()).containsExactly("press", "press")
    }
}
