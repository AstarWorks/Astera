package com.astarworks.astera.combat.application.admin

import com.astarworks.astera.combat.application.service.InMemoryGodModeService
import com.astarworks.astera.combat.domain.status.StatusEffectInstance
import com.astarworks.astera.combat.domain.status.StatusEffectSpec
import com.astarworks.astera.combat.domain.status.StatusKind
import com.astarworks.astera.combat.testfixtures.FakeStatusEffectContainer
import com.astarworks.astera.core.application.admin.panel.AdminPanelGuard
import com.astarworks.astera.core.application.admin.panel.AdminPanelPoint
import com.astarworks.astera.core.application.admin.panel.InMemoryAdminPanelRegistry
import com.astarworks.astera.core.application.service.DefaultGuiIntentRouter
import com.astarworks.astera.core.application.service.InMemoryAuditLogRepository
import com.astarworks.astera.core.application.service.InMemoryCooldownTracker
import com.astarworks.astera.core.domain.audit.AuditEvent
import com.astarworks.astera.core.domain.cooldown.CooldownKey
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.resource.ResourceType
import com.astarworks.astera.core.domain.status.StatusEffectId
import com.astarworks.astera.testfixtures.FakeAdminPermissionService
import com.astarworks.astera.testfixtures.FakeGuiAdapter
import com.astarworks.astera.testfixtures.FakeMcPlayer
import com.astarworks.astera.testfixtures.FakeMcServer
import com.astarworks.astera.testfixtures.FakeMessageRenderer
import com.astarworks.astera.testfixtures.FakePlayerHealthGateway
import com.astarworks.astera.testfixtures.FakeResourcePoolService
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class AdminCombatPanelTest {

    private val admin = FakeMcPlayer(PlayerId(UUID.randomUUID()), "admin")
    private val rookie = FakeMcPlayer(PlayerId(UUID.randomUUID()), "rookie")
    private val permissions = FakeAdminPermissionService(mutableSetOf(admin.id))
    private val audit = InMemoryAuditLogRepository()
    private val registry = InMemoryAdminPanelRegistry()
    private val health = FakePlayerHealthGateway()
    private val statuses = FakeStatusEffectContainer()
    private val cooldowns = InMemoryCooldownTracker(FakeScheduler())
    private val pools = FakeResourcePoolService()
    private val god = InMemoryGodModeService()
    private val gui = FakeGuiAdapter()
    private val router = DefaultGuiIntentRouter { gui }

    init {
        val server = FakeMcServer().apply { register(admin); register(rookie) }
        val tools = AdminCombatToolsUseCase(health, statuses, cooldowns, pools, god)
        AdminCombatPanel(AdminPanelGuard(permissions, FakeMessageRenderer(), audit), registry, tools, server)
            .install(router)
    }

    private fun on(p: FakeMcPlayer, key: String) =
        GuiIntent(key, mapOf("target" to p.id.toString()))

    @Test
    fun `contributes heal and statuses to player actions and five tools to self tools`() {
        assertThat(registry.tiles(AdminPanelPoint.PLAYER).map { it.intent.key })
            .containsExactly(AdminCombatPanel.HEAL, AdminCombatPanel.STATUSES)
        assertThat(registry.tiles(AdminPanelPoint.SELF).map { it.intent.key })
            .containsExactly(
                AdminCombatPanel.GOD, AdminCombatPanel.HEAL, AdminCombatPanel.COOLDOWNS, AdminCombatPanel.REFILL,
            )
    }

    @Test
    fun `non-admin cannot use any combat tool`() {
        listOf(AdminCombatPanel.GOD, AdminCombatPanel.HEAL, AdminCombatPanel.COOLDOWNS, AdminCombatPanel.REFILL)
            .forEach { router.dispatch(rookie, on(rookie, it)) }

        assertThat(god.isGod(rookie.id)).isFalse()
        assertThat(health.calls).isEmpty()
        assertThat(rookie.messages).hasSize(4).allMatch { it.contains("astera.admin.panel.denied") }
    }

    @Test
    fun `heal restores the Astera HP container to max and audits`() {
        health.setMaxHealth(rookie.id, 100.0)
        health.setHealth(rookie.id, 12.0, com.astarworks.astera.core.domain.player.physiology.HealthChangeSource.AdminCommand)

        router.dispatch(admin, on(rookie, AdminCombatPanel.HEAL))

        assertThat(health.getCurrentHealth(rookie.id)).isEqualTo(100.0)
        val ev = audit.recent(5).single() as AuditEvent.AdminActionPerformed
        assertThat(ev.actor).isEqualTo(admin.id)
        assertThat(ev.target).isEqualTo(rookie.id.toString())
    }

    @Test
    fun `statuses and cooldowns are cleared`() {
        val spec = StatusEffectSpec(StatusEffectId("burn"), MessageKey("test.burn"), StatusKind.PARALYSIS, 1.0, false, 1)
        statuses.apply(StatusEffectInstance(spec, rookie.id, 100, 1, 0L))
        cooldowns.start(rookie.id, CooldownKey.Skill("fireball"), 200)
        assertThat(cooldowns.isCoolingDown(rookie.id, CooldownKey.Skill("fireball"))).isTrue()

        router.dispatch(admin, on(rookie, AdminCombatPanel.STATUSES))
        router.dispatch(admin, on(rookie, AdminCombatPanel.COOLDOWNS))

        assertThat(statuses.active(rookie.id)).isEmpty()
        assertThat(cooldowns.activeKeysFor(rookie.id)).isEmpty()
    }

    @Test
    fun `refill tops ammo and energy up to the cap`() {
        pools.initialiseFor(admin.id)
        pools.spendResource(admin.id, ResourceType.Ammo, 20)
        pools.spendResource(admin.id, ResourceType.Energy, 50)

        router.dispatch(admin, on(admin, AdminCombatPanel.REFILL))

        val pool = pools.currentPool(admin.id)!!
        assertThat(pool.ammo).isEqualTo(30)
        assertThat(pool.energy).isEqualTo(100)
    }

    @Test
    fun `god mode toggles on and off`() {
        router.dispatch(admin, on(admin, AdminCombatPanel.GOD))
        assertThat(god.isGod(admin.id)).isTrue()
        router.dispatch(admin, on(admin, AdminCombatPanel.GOD))
        assertThat(god.isGod(admin.id)).isFalse()
    }
}
