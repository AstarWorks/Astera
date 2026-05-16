package com.astarworks.astera.application.usecase

import com.astarworks.astera.application.port.outbound.IMessageRenderer
import com.astarworks.astera.application.port.outbound.IPlayerGateway
import com.astarworks.astera.application.port.outbound.IWeaponRegistry
import com.astarworks.astera.domain.model.player.PlayerId
import com.astarworks.astera.domain.model.weapon.WeaponId
import org.slf4j.LoggerFactory

/**
 * Handles `/astera give <player> <weapon-id>`.
 *
 * Phase 1 trusts the caller; permission checks live in the platform's command
 * handler.
 */
class GiveWeaponUseCase(
    private val weapons: IWeaponRegistry,
    private val players: IPlayerGateway,
    private val msg: IMessageRenderer,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class Request(val invokerId: PlayerId?, val targetName: String, val weaponIdStr: String)

    enum class Outcome { SUCCESS, INVALID_WEAPON_ID, WEAPON_NOT_FOUND, PLAYER_NOT_FOUND }

    fun execute(req: Request): Outcome {
        val weaponId = runCatching { WeaponId(req.weaponIdStr) }.getOrNull()
            ?: return notify(req.invokerId, K_NOT_FOUND, weapon = req.weaponIdStr).let { Outcome.INVALID_WEAPON_ID }

        val spec = weapons.find(weaponId)
            ?: return notify(req.invokerId, K_NOT_FOUND, weapon = req.weaponIdStr).let { Outcome.WEAPON_NOT_FOUND }

        val target = players.findByName(req.targetName)
            ?: return notify(req.invokerId, K_USAGE).let { Outcome.PLAYER_NOT_FOUND }

        players.giveWeapon(target, spec)
        notify(
            req.invokerId,
            K_SUCCESS,
            placeholders = mapOf("player" to req.targetName, "weapon" to req.weaponIdStr),
        )
        log.info("Gave weapon {} to {}", req.weaponIdStr, req.targetName)
        return Outcome.SUCCESS
    }

    private fun notify(
        to: PlayerId?,
        key: String,
        weapon: String? = null,
        placeholders: Map<String, String> = emptyMap(),
    ) {
        if (to == null) return
        val merged = placeholders.toMutableMap().apply { if (weapon != null) put("weapon", weapon) }
        players.sendMessage(to, msg.render(to, key, merged))
    }

    companion object {
        const val K_SUCCESS = "astera.command.give.success"
        const val K_NOT_FOUND = "astera.command.give.not_found"
        const val K_USAGE = "astera.command.give.usage"
    }
}
