package com.astarworks.astera.combat.application.admin

import com.astarworks.astera.combat.application.usecase.skill.CastSkillError
import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.application.admin.panel.AdminIntents
import com.astarworks.astera.core.application.admin.panel.AdminPanelGuard
import com.astarworks.astera.core.application.admin.panel.AdminPanelPoint
import com.astarworks.astera.core.application.admin.panel.AdminPanelTile
import com.astarworks.astera.core.application.admin.panel.AdminPanelUi
import com.astarworks.astera.core.application.admin.panel.IAdminPanelRegistry
import com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.application.port.outbound.ISkillTesterDelivery
import com.astarworks.astera.core.application.port.outbound.SkillTesterError
import com.astarworks.astera.core.application.services.ServiceRegistry
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.gui.GuiText
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.skill.SkillId
import org.slf4j.LoggerFactory

/**
 * Combat's "Skills" page of the `/astera admin panel` (F2-5, ADR-0196; ADR-0183
 * registry): every skill as an icon; click casts it as the clicker, shift-click hands
 * out a skill tester item. Contributes the hub tile and owns the `admin.skills.` prefix.
 */
public class AdminSkillsPanel(
    private val guard: AdminPanelGuard,
    private val registry: IAdminPanelRegistry,
    private val caster: AdminSkillCaster,
    private val testers: () -> ISkillTesterDelivery?,
    private val guiAdapter: IGuiAdapter,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    public fun install(router: IGuiIntentRouter): List<ServiceRegistry.Registration> = listOf(
        registry.register(
            AdminPanelTile(
                id = "combat.skills",
                point = AdminPanelPoint.HUB,
                order = 15,
                icon = ItemId("gui-admin-skills"),
                name = GuiText(K_HUB),
                lore = listOf(GuiText(K_HUB_LORE)),
                intent = GuiIntent(AdminSkillCatalog.INTENT_OPEN, mapOf(AdminSkillCatalog.PAYLOAD_ARCHETYPE to "all")),
            ),
        ),
        router.register(AdminIntents.SKILLS_PREFIX, guard.guarded(::handle)),
    )

    /** `admin.skills.*` entry point (public so tests can drive it without a router). */
    public fun handle(intent: GuiIntent, player: IMcPlayer) {
        when (intent.key) {
            AdminSkillCatalog.INTENT_OPEN -> open(
                player,
                AdminSkillCatalog.parse(intent.payload[AdminSkillCatalog.PAYLOAD_ARCHETYPE]),
                intent.payload["page"]?.toIntOrNull() ?: 0,
            )
            AdminSkillCatalog.INTENT_CAST -> withSkill(player, intent) { cast(player, it) }
            AdminSkillCatalog.INTENT_TESTER -> withSkill(player, intent) { giveTester(player, it) }
            else -> log.warn("Unknown admin.skills intent '{}' from {}", intent.key, player.name)
        }
    }

    private fun open(player: IMcPlayer, filter: com.astarworks.astera.combat.domain.skill.SkillArchetype?, page: Int) {
        val spec = AdminSkillCatalog.buildSpec(caster.all(), filter, page)
        if (!AdminPanelUi.show(guiAdapter, player, spec)) guard.tell(player, K_OPEN_REJECTED)
    }

    private fun withSkill(player: IMcPlayer, intent: GuiIntent, block: (SkillId) -> Unit) {
        val skill = intent.payload[AdminSkillCatalog.PAYLOAD_SKILL]?.let { runCatching { SkillId(it) }.getOrNull() }
        if (skill == null) guard.tell(player, K_ERR_UNKNOWN, mapOf("skill" to intent.payload[AdminSkillCatalog.PAYLOAD_SKILL].orEmpty()))
        else block(skill)
    }

    private fun cast(player: IMcPlayer, skill: SkillId) {
        when (val r = caster.cast(player.id, skill)) {
            is Result.Success -> {
                // Close the panel so the effect is visible in the world.
                guiAdapter.close(player)
                guard.record(player, AdminSkillCatalog.INTENT_CAST, skill.value)
            }
            is Result.Failure -> guard.tell(player, errorKey(r.error), errorArgs(r.error, skill))
        }
    }

    private fun giveTester(player: IMcPlayer, skill: SkillId) {
        val delivery = testers()
        if (delivery == null) {
            guard.tell(player, K_ERR_NO_ITEMS)
            return
        }
        when (val r = delivery.give(player, skill)) {
            is Result.Success -> {
                guard.tell(player, K_TESTER_GIVEN, mapOf("skill" to skill.value))
                guard.record(player, AdminSkillCatalog.INTENT_TESTER, skill.value)
            }
            is Result.Failure -> guard.tell(
                player,
                when (r.error) {
                    SkillTesterError.NoTemplate -> K_ERR_NO_TEMPLATE
                    SkillTesterError.InventoryFull -> K_ERR_FULL
                    is SkillTesterError.Delivery -> K_ERR_DELIVERY
                },
                mapOf("skill" to skill.value),
            )
        }
    }

    public companion object {
        private fun k(s: String) = MessageKey("astera.admin.panel.skills.$s")

        private val K_HUB = k("hub")
        private val K_HUB_LORE = k("hub.lore")
        private val K_TESTER_GIVEN = k("tester-given")
        private val K_ERR_UNKNOWN = k("err.unknown")
        private val K_ERR_NO_ITEMS = k("err.no-items")
        private val K_ERR_NO_TEMPLATE = k("err.no-template")
        private val K_ERR_FULL = k("err.full")
        private val K_ERR_DELIVERY = k("err.delivery")
        private val K_ERR_COOLDOWN = k("err.cooldown")
        private val K_ERR_SILENCED = k("err.silenced")
        private val K_ERR_OTHER = k("err.cast")
        private val K_OPEN_REJECTED = MessageKey("astera.gui.error.open-rejected")

        /** Message key for a failed admin cast (shared with `/astera skill cast`). */
        public fun errorKey(e: CastSkillError): MessageKey = when (e) {
            is CastSkillError.SkillNotFound -> K_ERR_UNKNOWN
            is CastSkillError.OnCooldown -> K_ERR_COOLDOWN
            is CastSkillError.Silenced -> K_ERR_SILENCED
            else -> K_ERR_OTHER
        }

        public fun errorArgs(e: CastSkillError, skill: SkillId): Map<String, String> = when (e) {
            is CastSkillError.OnCooldown -> mapOf("skill" to skill.value, "seconds" to "%.1f".format(e.remainingMs / 1000.0))
            else -> mapOf("skill" to skill.value)
        }
    }
}
