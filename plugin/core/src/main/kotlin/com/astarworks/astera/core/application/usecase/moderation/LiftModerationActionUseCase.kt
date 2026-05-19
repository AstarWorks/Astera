package com.astarworks.astera.core.application.usecase.moderation

import com.astarworks.astera.core.application.port.outbound.IModerationService
import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.application.port.outbound.ModerationError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.moderation.ModerationActionId
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Lift a previously-applied moderation action (Wave 5h, ADR-0074 §4).
 *
 * Requires `astera.mod.lift` from [liftedBy]. Idempotent: lifting an
 * unknown / already-lifted id is a Success no-op per
 * [IModerationService.lift]'s contract.
 *
 * Permission denial returns [ModerationError.Storage] with the
 * [ApplyModerationActionUseCase.PERMISSION_DENIED_PREFIX] marker so
 * command-side mapping shares the same code path as
 * [ApplyModerationActionUseCase].
 */
public class LiftModerationActionUseCase(
    private val service: IModerationService,
    private val permissionService: IPermissionService,
) {

    public fun lift(actionId: ModerationActionId, liftedBy: PlayerId): Result<Unit, ModerationError> {
        val perm = Permission(PERM_LIFT)
        if (!permissionService.check(liftedBy, perm)) {
            return Result.failure(
                ModerationError.Storage(
                    "${ApplyModerationActionUseCase.PERMISSION_DENIED_PREFIX}:${perm.id}",
                ),
            )
        }
        return service.lift(actionId, liftedBy)
    }

    public companion object {
        public const val PERM_LIFT: String = "astera.mod.lift"
    }
}
