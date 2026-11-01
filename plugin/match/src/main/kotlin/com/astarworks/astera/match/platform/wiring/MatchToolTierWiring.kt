package com.astarworks.astera.match.platform.wiring

import com.astarworks.astera.core.application.port.outbound.IMatchPlayerLevel
import com.astarworks.astera.core.application.port.outbound.presentation.IPresentation
import com.astarworks.astera.core.application.service.inventory.MatchLoanerKit
import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.application.services.register
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.presentation.PresentationMoments
import com.astarworks.astera.match.application.service.NormaLevelService
import com.astarworks.astera.match.domain.event.NormaLevelUp

/**
 * Match-time field tools follow the Norma level (ADR-0319): Core lends the tier of the player's current level on match
 * entry and rejoin (it asks [IMatchPlayerLevel], registered here), and a [NormaLevelUp] that opens a new tier upgrades
 * the carried tools in place through Core's [MatchLoanerKit] and plays the `norma-tools-up` moment.
 */
internal object MatchToolTierWiring {

    fun install(env: MatchWiringEnv, norma: NormaLevelService) {
        val services = env.ctx.services
        env.ctx.registrations += services.register<IMatchPlayerLevel>(IMatchPlayerLevel { norma.levelOf(it) }, priority = 0)
        val subs = env.ctx.subscriptions
        val eventBus = env.eventBus
        subs += eventBus.subscribe(NormaLevelUp::class) { e ->
            val change = services.get<MatchLoanerKit>()?.upgradeTools(e.playerId, e.level) ?: return@subscribe
            // "iron-pickaxe" -> tier "iron": the material prefix names the tier in the message.
            val tier = change.tools.first().value.substringBefore('-')
            val tierName = env.msgRenderer.render(e.playerId, MessageKey("astera.match.norma-tools.tier.$tier"))
            services.get<IPresentation>()?.play(PresentationMoments.NORMA_TOOLS_UP, e.playerId, mapOf("tier" to tierName))
        }
    }
}
