package com.astarworks.astera.core.application.port.outbound.lobby

/**
 * The queue lanes the lobby builds pads for, in display order. Core cannot read the Match
 * plugin's queue settings (ADR-0022), so Match registers this port from
 * `content/matchmaking/queue.yaml`; no lane list is hard-coded in Core.
 */
public fun interface ILobbyLaneCatalog {
    public fun laneIds(): List<String>
}
