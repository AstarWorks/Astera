package com.astarworks.astera.core.application.port.outbound.presentation

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.presentation.AnnounceError
import com.astarworks.astera.core.domain.presentation.AnnounceTarget

/** Plays a table moment for every online player on the targeted backends. */
public interface IAnnouncer {
    public fun announce(moment: String, target: AnnounceTarget, args: Map<String, String> = emptyMap()): Result<Unit, AnnounceError>
}
