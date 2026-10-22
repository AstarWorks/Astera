package com.astarworks.astera.combat.application.admin

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.application.admin.panel.AdminIntents
import com.astarworks.astera.core.application.admin.panel.AdminPanelGuard
import com.astarworks.astera.core.application.admin.panel.AdminPanelPoint
import com.astarworks.astera.core.application.admin.panel.AdminPanelTile
import com.astarworks.astera.core.application.admin.panel.AdminPanelUi
import com.astarworks.astera.core.application.admin.panel.IAdminPanelRegistry
import com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter
import com.astarworks.astera.core.application.services.ServiceRegistry
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.gui.GuiText
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Combat section of the `/astera-admin` panel (F1-D, ADR-0183): god mode,
 * heal (Astera HP), clear statuses / cooldowns, refill resources. Contributes
 * tiles to the Players page (heal, statuses) and the Self-tools page (all
 * five) and owns the `admin.combat.` intent prefix. The panel injects
 * `target=<uuid>` into every tile intent.
 */
public class AdminCombatPanel(
    private val guard: AdminPanelGuard,
    private val registry: IAdminPanelRegistry,
    private val tools: AdminCombatToolsUseCase,
    private val mcServer: IMcServer,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    public fun install(router: IGuiIntentRouter): List<ServiceRegistry.Registration> {
        val regs = mutableListOf<ServiceRegistry.Registration>()
        regs += registry.register(tile("combat.player.heal", AdminPanelPoint.PLAYER, 20, "gui-admin-heal", "heal", HEAL))
        regs += registry.register(tile("combat.player.statuses", AdminPanelPoint.PLAYER, 30, "gui-admin-statuses", "statuses", STATUSES))
        regs += registry.register(tile("combat.self.god", AdminPanelPoint.SELF, 20, "gui-admin-god", "god", GOD))
        regs += registry.register(tile("combat.self.heal", AdminPanelPoint.SELF, 30, "gui-admin-heal", "heal", HEAL))
        regs += registry.register(tile("combat.self.cooldowns", AdminPanelPoint.SELF, 40, "gui-admin-cooldowns", "cooldowns", COOLDOWNS))
        regs += registry.register(tile("combat.self.refill", AdminPanelPoint.SELF, 50, "gui-admin-refill", "refill", REFILL))
        regs += router.register(AdminIntents.COMBAT_PREFIX, guard.guarded(::handle))
        return regs
    }

    /** `admin.combat.*` entry point (public for tests). */
    public fun handle(intent: GuiIntent, player: IMcPlayer) {
        val target = intent.payload[AdminPanelUi.PAYLOAD_TARGET]
            ?.let { runCatching { PlayerId(UUID.fromString(it)) }.getOrNull() }
        if (target == null) {
            guard.tell(player, K_BAD_TARGET)
            return
        }
        val name = mcServer.findPlayer(target)?.name ?: target.toString()
        val args = mapOf("player" to name)
        when (intent.key) {
            HEAL -> done(player, intent.key, target, K_HEALED, args, tools.healFull(target))
            STATUSES -> done(player, intent.key, target, K_STATUSES_CLEARED, args, tools.clearStatuses(target))
            COOLDOWNS -> when (val r = tools.clearCooldowns(target)) {
                is Result.Success -> {
                    guard.tell(player, K_COOLDOWNS_CLEARED, args + ("count" to r.value.toString()))
                    guard.record(player, intent.key, target.toString())
                }
                is Result.Failure -> guard.tell(player, errorKey(r.error), args)
            }
            REFILL -> done(player, intent.key, target, K_REFILLED, args, tools.refillResources(target))
            GOD -> {
                val on = tools.toggleGod(target)
                guard.tell(player, if (on) K_GOD_ON else K_GOD_OFF, args)
                guard.record(player, intent.key, target.toString(), if (on) "on" else "off")
            }
            else -> log.warn("Unknown admin.combat intent '{}' from {}", intent.key, player.name)
        }
    }

    private fun done(
        player: IMcPlayer,
        action: String,
        target: PlayerId,
        okKey: MessageKey,
        args: Map<String, String>,
        result: Result<Unit, AdminToolError>,
    ) {
        when (result) {
            is Result.Success -> {
                guard.tell(player, okKey, args)
                guard.record(player, action, target.toString())
            }
            is Result.Failure -> guard.tell(player, errorKey(result.error), args)
        }
    }

    private fun errorKey(e: AdminToolError): MessageKey = when (e) {
        is AdminToolError.NoHealth -> K_ERR_NO_HEALTH
        is AdminToolError.Unavailable -> K_ERR_UNAVAILABLE
        is AdminToolError.NoResourcePool -> K_ERR_NO_POOL
    }

    private fun tile(id: String, point: AdminPanelPoint, order: Int, icon: String, name: String, intent: String) =
        AdminPanelTile(
            id = id,
            point = point,
            order = order,
            icon = ItemId(icon),
            name = GuiText(k("tile.$name")),
            lore = listOf(GuiText(k("tile.$name.lore"))),
            intent = GuiIntent(intent),
        )

    public companion object {
        public const val HEAL: String = "admin.combat.heal"
        public const val STATUSES: String = "admin.combat.statuses"
        public const val COOLDOWNS: String = "admin.combat.cooldowns"
        public const val REFILL: String = "admin.combat.refill"
        public const val GOD: String = "admin.combat.god"

        private fun k(s: String) = MessageKey("astera.admin.panel.combat.$s")

        private val K_HEALED = k("healed")
        private val K_STATUSES_CLEARED = k("statuses-cleared")
        private val K_COOLDOWNS_CLEARED = k("cooldowns-cleared")
        private val K_REFILLED = k("refilled")
        private val K_GOD_ON = k("god.on")
        private val K_GOD_OFF = k("god.off")
        private val K_BAD_TARGET = k("err.bad-target")
        private val K_ERR_NO_HEALTH = k("err.no-health")
        private val K_ERR_UNAVAILABLE = k("err.unavailable")
        private val K_ERR_NO_POOL = k("err.no-pool")
    }
}
