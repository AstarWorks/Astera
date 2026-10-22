package com.astarworks.astera.core.application.port.outbound.lobby

/**
 * Live "players queued" count per lane, shown by the holograms above the lobby lane pads.
 * Match pushes counts on queue changes; the Paper adapter renders them.
 */
public interface ILobbyLaneBoard {
    /** Sets the queued count of [laneId]; listeners fire only when it changed. */
    public fun setQueued(laneId: String, queued: Int)

    public fun queued(laneId: String): Int

    /** Registers [listener] (called with the lane id after each change). Returns a cancel handle. */
    public fun onChanged(listener: (laneId: String) -> Unit): AutoCloseable
}
