package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.game.EntityRef
import com.astarworks.astera.core.domain.game.GameScope

public fun interface IGameScopeResolver {
    public fun resolve(entity: EntityRef): GameScope
}
