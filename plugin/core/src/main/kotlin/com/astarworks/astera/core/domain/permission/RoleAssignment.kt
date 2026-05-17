package com.astarworks.astera.core.domain.permission

import com.astarworks.astera.core.domain.player.PlayerId

/** Records that a player has been granted a specific role. */
public data class RoleAssignment(
    val playerId: PlayerId,
    val roleId: RoleId,
)
