package com.astarworks.astera.application.usecase

import com.astarworks.astera.application.port.outbound.IMessageRenderer
import com.astarworks.astera.application.port.outbound.IPlayerGateway
import com.astarworks.astera.application.port.outbound.IWeaponRegistry
import com.astarworks.astera.domain.Result
import com.astarworks.astera.domain.model.i18n.MessageKey
import com.astarworks.astera.domain.model.player.PlayerId
import com.astarworks.astera.domain.model.weapon.WeaponId
import org.slf4j.LoggerFactory

/**
 * Handles `/astera give <player> <weapon-id>`.
 *
 * Phase 1 trusts the caller; permission checks live in the platform's command
 * handler.
 *
 * Returns [Result<Unit, GiveWeaponError>]. Each error case carries an
 * [com.astarworks.astera.domain.model.i18n.MessageKey] so callers can render
 * a user-friendly message without duplicating the failure→string mapping.
 */
public class GiveWeaponUseCase(
    private val weapons: IWeaponRegistry,
    private val players: IPlayerGateway,
    private val msg: IMessageRenderer,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    public data class Request(
        val invokerId: PlayerId?,
        val targetName: String,
        val weaponIdStr: String,
    )

    public fun execute(req: Request): Result<Unit, GiveWeaponError> {
        val weaponId = runCatching { WeaponId(req.weaponIdStr) }.getOrNull()
            ?: return fail(req, GiveWeaponError.InvalidWeaponId(req.weaponIdStr))

        val spec = weapons.find(weaponId)
            ?: return fail(req, GiveWeaponError.WeaponNotFound(req.weaponIdStr))

        val target = players.findByName(req.targetName)
            ?: return fail(req, GiveWeaponError.PlayerNotFound(req.targetName))

        players.giveWeapon(target, spec)
        notify(
            req.invokerId,
            K_SUCCESS,
            placeholders = mapOf("player" to req.targetName, "weapon" to req.weaponIdStr),
        )
        log.info("Gave weapon {} to {}", req.weaponIdStr, req.targetName)
        return Result.success(Unit)
    }

    private fun fail(req: Request, error: GiveWeaponError): Result<Unit, GiveWeaponError> {
        notify(
            req.invokerId,
            error.messageKey,
            placeholders = mapOf("weapon" to req.weaponIdStr, "player" to req.targetName),
        )
        return Result.failure(error)
    }

    private fun notify(to: PlayerId?, key: MessageKey, placeholders: Map<String, String> = emptyMap()) {
        if (to == null) return
        players.sendMessage(to, msg.render(to, key, placeholders))
    }

    public companion object {
        public val K_SUCCESS: MessageKey = MessageKey("astera.command.give.success")
    }
}

/** Expected failure modes of [GiveWeaponUseCase.execute]. */
public sealed class GiveWeaponError {
    public abstract val messageKey: MessageKey

    public data class InvalidWeaponId(val raw: String) : GiveWeaponError() {
        override val messageKey: MessageKey = MessageKey("astera.command.give.invalid_id")
    }

    public data class WeaponNotFound(val raw: String) : GiveWeaponError() {
        override val messageKey: MessageKey = MessageKey("astera.command.give.not_found")
    }

    public data class PlayerNotFound(val targetName: String) : GiveWeaponError() {
        override val messageKey: MessageKey = MessageKey("astera.command.give.player_not_found")
    }
}
