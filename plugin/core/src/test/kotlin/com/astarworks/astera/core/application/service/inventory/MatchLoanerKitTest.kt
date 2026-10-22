package com.astarworks.astera.core.application.service.inventory

import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.item.IOwnedItemQuery
import com.astarworks.astera.core.domain.i18n.LocalizedLore
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.item.DropPolicy
import com.astarworks.astera.core.domain.item.DurabilityPolicy
import com.astarworks.astera.core.domain.item.EquipSlot
import com.astarworks.astera.core.domain.item.ItemAttributes
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.item.ItemSkillBinding
import com.astarworks.astera.core.domain.item.ItemSpec
import com.astarworks.astera.core.domain.item.ItemTemplate
import com.astarworks.astera.core.domain.item.Rarity
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.skill.SkillTrigger
import com.astarworks.astera.testfixtures.FakeItemDelivery
import com.astarworks.astera.testfixtures.FakeMcPlayer
import com.astarworks.astera.testfixtures.FakeMcServer
import com.astarworks.astera.testfixtures.FakeMessageRenderer
import com.astarworks.astera.testfixtures.FakePlayerGateway
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class MatchLoanerKitTest {

    private val playerId = PlayerId(UUID.randomUUID())
    private val sword = spec("knight-sword", armed = true)
    private val junk = spec("gui-pane-dark", armed = false)
    private val registry = object : IItemRegistry {
        private val all = listOf(sword, junk).associateBy { it.id }
        override fun find(id: ItemId): ItemSpec? = all[id]
        override fun all(): Collection<ItemSpec> = all.values
    }

    private var carried: List<ItemId> = emptyList()
    private lateinit var delivery: FakeItemDelivery
    private lateinit var gateway: FakePlayerGateway
    private lateinit var kit: MatchLoanerKit

    @BeforeEach
    fun setUp() {
        delivery = FakeItemDelivery()
        gateway = FakePlayerGateway()
        kit = build(owned = IOwnedItemQuery { carried })
    }

    private fun build(owned: IOwnedItemQuery?, items: List<ItemId> = listOf(sword.id)) = MatchLoanerKit(
        owned = { owned },
        items = registry,
        delivery = { delivery },
        server = FakeMcServer().also { it.register(FakeMcPlayer(id = playerId)) },
        players = gateway,
        messages = FakeMessageRenderer(),
        kitItems = { items },
    )

    @Test
    fun `an unarmed player is lent the starter weapons and told`() {
        carried = listOf(junk.id)

        assertThat(kit.ensureArmed(playerId)).isTrue()

        assertThat(delivery.delivered.map { it.itemId }).containsExactly(sword.id)
        assertThat(gateway.messages).hasSize(1)
    }

    @Test
    fun `a player carrying a weapon keeps their own kit`() {
        carried = listOf(junk.id, sword.id)

        assertThat(kit.ensureArmed(playerId)).isFalse()

        assertThat(delivery.delivered).isEmpty()
        assertThat(gateway.messages).isEmpty()
    }

    @Test
    fun `nothing is lent without the query port or a resolvable kit`() {
        assertThat(build(owned = null).ensureArmed(playerId)).isFalse()
        assertThat(build(owned = { carried }, items = listOf(ItemId("missing"))).ensureArmed(playerId)).isFalse()

        assertThat(delivery.delivered).isEmpty()
    }

    private fun spec(id: String, armed: Boolean) = ItemSpec(
        id = ItemId(id),
        template = ItemTemplate.Vanilla(material = "IRON_SWORD"),
        displayName = LocalizedText.EMPTY,
        lore = LocalizedLore.EMPTY,
        rarity = Rarity.COMMON,
        levelRequirement = 1,
        attributes = ItemAttributes.EMPTY,
        equipSlot = EquipSlot.MAINHAND,
        durability = DurabilityPolicy.DEFAULT,
        dropPolicy = DropPolicy.DEFAULT,
        enchantable = true,
        glint = false,
        skillBindings = if (armed) listOf(ItemSkillBinding(SkillTrigger.LEFT_CLICK, SkillId("sword-slash"))) else emptyList(),
        pdcTags = emptyMap(),
    )
}
