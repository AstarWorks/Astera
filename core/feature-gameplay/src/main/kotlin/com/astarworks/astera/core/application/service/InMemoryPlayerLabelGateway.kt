package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.port.outbound.IPlayerLabelGateway
import com.astarworks.astera.core.domain.hud.PlayerLabel
import com.astarworks.astera.core.domain.player.PlayerId
import java.util.concurrent.ConcurrentHashMap

/** Records what each viewer was told (simulator / tests); renders nothing. */
public class InMemoryPlayerLabelGateway : IPlayerLabelGateway {

    private val byViewer = ConcurrentHashMap<PlayerId, Map<PlayerId, PlayerLabel>>()

    override fun apply(viewer: PlayerId, labels: Map<PlayerId, PlayerLabel>) {
        if (labels.isEmpty()) byViewer.remove(viewer) else byViewer[viewer] = labels.toMap()
    }

    override fun clear(viewer: PlayerId) {
        byViewer.remove(viewer)
    }

    /** What [viewer] currently sees styled; empty when plain. */
    public fun labelsOf(viewer: PlayerId): Map<PlayerId, PlayerLabel> = byViewer[viewer].orEmpty()
}
