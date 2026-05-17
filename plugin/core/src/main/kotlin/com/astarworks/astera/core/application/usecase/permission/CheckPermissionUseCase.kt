package com.astarworks.astera.core.application.usecase.permission

import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.core.domain.player.PlayerId

/** Always returns Success(bool); fail-closed (false) when player has no assignments. */
public class CheckPermissionUseCase(private val svc: IPermissionService) {

    public fun execute(playerId: PlayerId, permission: Permission): Result<Boolean, Nothing> =
        Result.success(svc.check(playerId, permission))
}
