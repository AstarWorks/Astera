package com.astarworks.astera.core.application.usecase.permission

import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.application.port.outbound.RevokeRoleError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.permission.RoleId
import com.astarworks.astera.core.domain.player.PlayerId

public class RevokeRoleUseCase(private val svc: IPermissionService) {

    public fun execute(playerId: PlayerId, roleId: RoleId): Result<Unit, RevokeRoleError> =
        svc.revoke(playerId, roleId)
}
