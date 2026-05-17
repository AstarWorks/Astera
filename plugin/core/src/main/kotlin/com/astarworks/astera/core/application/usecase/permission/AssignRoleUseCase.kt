package com.astarworks.astera.core.application.usecase.permission

import com.astarworks.astera.core.application.port.outbound.AssignRoleError
import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.permission.RoleId
import com.astarworks.astera.core.domain.player.PlayerId

public class AssignRoleUseCase(private val svc: IPermissionService) {

    public fun execute(playerId: PlayerId, roleId: RoleId): Result<Unit, AssignRoleError> =
        svc.assign(playerId, roleId)
}
