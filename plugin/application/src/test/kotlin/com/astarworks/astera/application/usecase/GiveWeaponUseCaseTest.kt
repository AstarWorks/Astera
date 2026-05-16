package com.astarworks.astera.application.usecase

import com.astarworks.astera.application.port.outbound.IMessageRenderer
import com.astarworks.astera.application.port.outbound.IPlayerGateway
import com.astarworks.astera.application.port.outbound.IWeaponRegistry
import com.astarworks.astera.domain.model.player.PlayerId
import com.astarworks.astera.domain.model.weapon.DamageAttribute
import com.astarworks.astera.domain.model.weapon.DamageProfile
import com.astarworks.astera.domain.model.weapon.Rarity
import com.astarworks.astera.domain.model.weapon.WeaponArchetype
import com.astarworks.astera.domain.model.weapon.WeaponId
import com.astarworks.astera.domain.model.weapon.WeaponSpec
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class GiveWeaponUseCaseTest {

    private val spec = WeaponSpec(
        id = WeaponId("example-sword"),
        displayNameKey = "astera.weapon.example-sword.name",
        loreKey = "astera.weapon.example-sword.lore",
        archetype = WeaponArchetype.SWORD,
        rarity = Rarity.COMMON,
        levelRequirement = 1,
        materialKey = "IRON_SWORD",
        damage = DamageProfile(8.0, DamageAttribute.PHYSICAL),
        cooldownTicks = 20,
    )

    private val targetId = PlayerId(UUID.randomUUID())

    private val weapons = object : IWeaponRegistry {
        override fun find(id: WeaponId) = if (id == spec.id) spec else null
        override fun all() = listOf(spec)
    }

    private val players = object : IPlayerGateway {
        val given = mutableListOf<Pair<PlayerId, WeaponSpec>>()
        val messages = mutableListOf<Pair<PlayerId, String>>()

        override fun findByName(name: String): PlayerId? = if (name == "Alice") targetId else null
        override fun sendMessage(playerId: PlayerId, rendered: String) {
            messages += playerId to rendered
        }
        override fun giveWeapon(playerId: PlayerId, weapon: WeaponSpec) {
            given += playerId to weapon
        }
    }

    private val msg = object : IMessageRenderer {
        override fun render(playerId: PlayerId?, key: String, placeholders: Map<String, String>) = key
        override fun renderLore(playerId: PlayerId?, key: String, placeholders: Map<String, String>) = listOf(key)
    }

    @Test
    fun `success path gives weapon and notifies invoker`() {
        val uc = GiveWeaponUseCase(weapons, players, msg)
        val invokerId = PlayerId(UUID.randomUUID())

        val outcome = uc.execute(
            GiveWeaponUseCase.Request(invokerId = invokerId, targetName = "Alice", weaponIdStr = "example-sword")
        )

        assertThat(outcome).isEqualTo(GiveWeaponUseCase.Outcome.SUCCESS)
        assertThat(players.given).containsExactly(targetId to spec)
        assertThat(players.messages.map { it.first }).containsExactly(invokerId)
    }

    @Test
    fun `unknown weapon yields WEAPON_NOT_FOUND`() {
        val uc = GiveWeaponUseCase(weapons, players, msg)
        val outcome = uc.execute(
            GiveWeaponUseCase.Request(null, "Alice", "missing-weapon")
        )
        assertThat(outcome).isEqualTo(GiveWeaponUseCase.Outcome.WEAPON_NOT_FOUND)
        assertThat(players.given).isEmpty()
    }

    @Test
    fun `invalid weapon id (uppercase) yields INVALID_WEAPON_ID`() {
        val uc = GiveWeaponUseCase(weapons, players, msg)
        val outcome = uc.execute(
            GiveWeaponUseCase.Request(null, "Alice", "Example-Sword")
        )
        assertThat(outcome).isEqualTo(GiveWeaponUseCase.Outcome.INVALID_WEAPON_ID)
    }

    @Test
    fun `missing player yields PLAYER_NOT_FOUND`() {
        val uc = GiveWeaponUseCase(weapons, players, msg)
        val outcome = uc.execute(
            GiveWeaponUseCase.Request(null, "Bob", "example-sword")
        )
        assertThat(outcome).isEqualTo(GiveWeaponUseCase.Outcome.PLAYER_NOT_FOUND)
    }
}
