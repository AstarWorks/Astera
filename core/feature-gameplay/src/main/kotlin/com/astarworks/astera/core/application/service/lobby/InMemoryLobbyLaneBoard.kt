package com.astarworks.astera.core.application.service.lobby

import com.astarworks.astera.core.application.port.outbound.lobby.ILobbyLaneBoard
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Thread-safe [ILobbyLaneBoard]. */
public class InMemoryLobbyLaneBoard : ILobbyLaneBoard {
    private val counts = ConcurrentHashMap<String, Int>()
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    override fun setQueued(laneId: String, queued: Int) {
        val value = queued.coerceAtLeast(0)
        if (counts.put(laneId, value) != value) listeners.forEach { it(laneId) }
    }

    override fun queued(laneId: String): Int = counts[laneId] ?: 0

    override fun onChanged(listener: (String) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }
}
