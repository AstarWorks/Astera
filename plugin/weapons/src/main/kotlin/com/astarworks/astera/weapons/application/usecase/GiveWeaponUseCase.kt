package com.astarworks.astera.weapons.application.usecase

import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.weapons.application.port.outbound.DeliveryResult
import com.astarworks.astera.weapons.application.port.outbound.IWeaponDelivery
import com.astarworks.astera.weapons.application.port.outbound.IWeaponRegistry
import com.astarworks.astera.weapons.domain.weapon.WeaponId
import org.slf4j.LoggerFactory

/**
 * Handles `/astera-weapons give <player> <weapon-id>`.
 *
 * Phase 1 trusts the caller; permission checks live in the platform's command
 * handler.
 *
 * Returns [Result<Unit, GiveWeaponError>]. Each error case carries an
 * [com.astarworks.astera.core.domain.i18n.MessageKey] so callers can render
 * a user-friendly message without duplicating the failure→string mapping.
 *
 * ADR-0022 Phase 3 wiring: weapon delivery goes through the Weapons-owned
 * [IWeaponDelivery] port rather than Core's `IPlayerGateway`, which keeps
 * Core weapon-agnostic. [players] is still injected for player lookup
 * ([IPlayerGateway.findByName]) and invoker messaging ([IPlayerGateway.sendMessage]).
 * The actual `IMcPlayer` for delivery is resolved via [server] which is the
 * cleanest way to bridge `PlayerId` → `IMcPlayer` without re-introducing a
 * weapon-specific method onto `IPlayerGateway`.
 */
public class GiveWeaponUseCase(
    private val weapons: IWeaponRegistry,
    private val players: IPlayerGateway,
    private val server: IMcServer,
    private val delivery: IWeaponDelivery,
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

        val targetId = players.findByName(req.targetName)
            ?: return fail(req, GiveWeaponError.PlayerNotFound(req.targetName))

        // Resolve PlayerId → IMcPlayer via Core's IMcServer. If the player
        // logged off between the name lookup and this lookup we report
        // PlayerNotFound (same message bucket — caller doesn't care which
        // moment the disconnect happened in).
        val targetPlayer = server.findPlayer(targetId)
            ?: return fail(req, GiveWeaponError.PlayerNotFound(req.targetName))

        return when (val outcome = delivery.give(targetPlayer, spec)) {
            is DeliveryResult.Delivered -> {
                notify(
                    req.invokerId,
                    K_SUCCESS,
                    placeholders = mapOf("player" to req.targetName, "weapon" to req.weaponIdStr),
                )
                log.info("Gave weapon {} to {}", req.weaponIdStr, req.targetName)
                Result.success(Unit)
            }
            is DeliveryResult.InventoryFull -> fail(req, GiveWeaponError.InventoryFull(req.targetName))
            is DeliveryResult.UnknownItem -> fail(req, GiveWeaponError.UnknownItem(outcome.itemKey))
        }
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
        public val K_SUCCESS: MessageKey = MessageKey("astera.weapons.command.give.success")
    }
}

/** Expected failure modes of [GiveWeaponUseCase.execute]. */
public sealed class GiveWeaponError {
    public abstract val messageKey: MessageKey

    public data class InvalidWeaponId(val raw: String) : GiveWeaponError() {
        override val messageKey: MessageKey = MessageKey("astera.weapons.command.give.invalid_id")
    }

    public data class WeaponNotFound(val raw: String) : GiveWeaponError() {
        override val messageKey: MessageKey = MessageKey("astera.weapons.command.give.not_found")
    }

    public data class PlayerNotFound(val targetName: String) : GiveWeaponError() {
        override val messageKey: MessageKey = MessageKey("astera.weapons.command.give.player_not_found")
    }

    public data class InventoryFull(val targetName: String) : GiveWeaponError() {
        override val messageKey: MessageKey = MessageKey("astera.weapons.command.give.inventory_full")
    }

    public data class UnknownItem(val itemKey: String) : GiveWeaponError() {
        override val messageKey: MessageKey = MessageKey("astera.weapons.command.give.unknown_item")
    }
}
