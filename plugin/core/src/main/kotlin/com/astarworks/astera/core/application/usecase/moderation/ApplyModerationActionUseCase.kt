package com.astarworks.astera.core.application.usecase.moderation

import com.astarworks.astera.core.application.port.outbound.IModerationService
import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.application.port.outbound.ModerationError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.moderation.ModerationAction
import com.astarworks.astera.core.domain.permission.Permission

/**
 * Apply a moderation action against a player (Wave 5h, ADR-0079 §4).
 *
 * Permission gating per variant (the actor is `action.issuedBy`):
 *
 * | Variant            | Required permission       |
 * |--------------------|---------------------------|
 * | [ModerationAction.Mute]          | `astera.mod.mute`         |
 * | [ModerationAction.Kick]          | `astera.mod.kick`         |
 * | [ModerationAction.Ban]           | `astera.mod.ban`          |
 * | [ModerationAction.ChatRateLimit] | `astera.mod.ratelimit`    |
 *
 * Permission denial returns [ModerationError.Storage] with a stable
 * `permission_denied` prefix — callers (command spec) translate to a
 * user-facing `astera.permission.denied` message. We intentionally don't
 * widen [ModerationError] just for permission denial since the port
 * doesn't know about permissions.
 *
 * The `when` over [ModerationAction] is exhaustive and MUST NOT carry an
 * `else` branch (Konsist enforces; per ADR-0079 §4).
 */
public class ApplyModerationActionUseCase(
    private val service: IModerationService,
    private val permissionService: IPermissionService,
) {

    public fun apply(action: ModerationAction): Result<ModerationAction, ModerationError> {
        val required = requiredPermission(action)
        if (!permissionService.check(action.issuedBy, required)) {
            return Result.failure(
                ModerationError.Storage("$PERMISSION_DENIED_PREFIX:${required.id}"),
            )
        }
        return service.apply(action)
    }

    private fun requiredPermission(action: ModerationAction): Permission = when (action) {
        is ModerationAction.Mute -> Permission(PERM_MUTE)
        is ModerationAction.Kick -> Permission(PERM_KICK)
        is ModerationAction.Ban -> Permission(PERM_BAN)
        is ModerationAction.ChatRateLimit -> Permission(PERM_RATELIMIT)
    }

    public companion object {
        public const val PERM_MUTE: String = "astera.mod.mute"
        public const val PERM_KICK: String = "astera.mod.kick"
        public const val PERM_BAN: String = "astera.mod.ban"
        public const val PERM_RATELIMIT: String = "astera.mod.ratelimit"

        /**
         * Stable prefix on [ModerationError.Storage.detail] when the actor
         * lacked the required permission node. Callers (e.g. command spec)
         * branch on this to render `astera.permission.denied` instead of a
         * generic storage error.
         */
        public const val PERMISSION_DENIED_PREFIX: String = "permission_denied"
    }
}
