package com.astarworks.astera.economy.application.admin

import com.astarworks.astera.core.application.admin.panel.AdminPanelGuard
import com.astarworks.astera.core.application.admin.panel.AdminPanelPoint
import com.astarworks.astera.core.application.admin.panel.InMemoryAdminPanelRegistry
import com.astarworks.astera.core.application.service.DefaultGuiIntentRouter
import com.astarworks.astera.core.application.service.InMemoryAuditLogRepository
import com.astarworks.astera.core.domain.audit.AuditEvent
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.gui.GuiSlot
import com.astarworks.astera.core.domain.gui.SlotIndex
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.economy.application.service.InMemoryCurrencyLedger
import com.astarworks.astera.economy.application.usecase.currency.DepositUseCase
import com.astarworks.astera.testfixtures.FakeAdminPermissionService
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakeGuiAdapter
import com.astarworks.astera.testfixtures.FakeMcPlayer
import com.astarworks.astera.testfixtures.FakeMessageRenderer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class AdminCurrencyPanelTest {

    private val admin = FakeMcPlayer(PlayerId(UUID.randomUUID()), "admin")
    private val rookie = FakeMcPlayer(PlayerId(UUID.randomUUID()), "rookie")
    private val permissions = FakeAdminPermissionService(mutableSetOf(admin.id))
    private val audit = InMemoryAuditLogRepository()
    private val registry = InMemoryAdminPanelRegistry()
    private val ledger = InMemoryCurrencyLedger()
    private val gui = FakeGuiAdapter()
    private val router = DefaultGuiIntentRouter { gui }
    private val panel: AdminCurrencyPanel

    init {
        val guard = AdminPanelGuard(permissions, FakeMessageRenderer(), audit)
        panel = AdminCurrencyPanel(guard, registry, gui, DepositUseCase(ledger, FakeDomainEventBus()))
        panel.install(router)
    }

    private fun grant(who: FakeMcPlayer, target: FakeMcPlayer, currency: String, amount: String) =
        router.dispatch(
            who,
            GuiIntent(
                AdminCurrencyPanel.GRANT,
                mapOf("target" to target.id.toString(), "currency" to currency, "amount" to amount),
            ),
        )

    @Test
    fun `registers a player action tile`() {
        assertThat(registry.tiles(AdminPanelPoint.PLAYER).single().intent.key).isEqualTo(AdminCurrencyPanel.MENU)
    }

    @Test
    fun `non-admin cannot grant`() {
        grant(rookie, rookie, "gold", "1000")
        assertThat(ledger.balance(rookie.id, Currency.GOLD)).isZero()
        assertThat(rookie.messages.single()).contains("astera.admin.panel.denied")
    }

    @Test
    fun `admin grant credits the target wallet and audits`() {
        val before = ledger.balance(rookie.id, Currency.GOLD)
        grant(admin, rookie, "gold", "1000")
        assertThat(ledger.balance(rookie.id, Currency.GOLD)).isEqualTo(before + 1000)
        val ev = audit.recent(5).single() as AuditEvent.AdminActionPerformed
        assertThat(ev.target).isEqualTo(rookie.id.toString())
        assertThat(ev.detail).contains("gold").contains("1000")
    }

    @Test
    fun `invalid currency or amount grants nothing`() {
        grant(admin, rookie, "doubloons", "5")
        grant(admin, rookie, "gold", "-5")
        assertThat(ledger.balance(rookie.id, Currency.GOLD)).isZero()
    }

    @Test
    fun `menu lists a preset per currency and amount`() {
        val spec = panel.buildMenu(rookie.id)
        val presets = (0..35).mapNotNull { spec.slotAt(SlotIndex(it)) as? GuiSlot.Static }
            .mapNotNull { it.onLeftClick }.filter { it.key == AdminCurrencyPanel.GRANT }
        assertThat(presets).hasSize(Currency.entries.size * AdminCurrencyPanel.PRESETS.size)
    }
}
