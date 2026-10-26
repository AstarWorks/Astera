package com.astarworks.astera.core.application.port.outbound.admin

/**
 * Rebuilds the procedural lobby world on a lobby-hosting backend (ADR-0180) without a restart:
 * the old build is cleared and the plan is pasted again, picking up edited `lobby-world.yaml` /
 * queue lanes. The Paper adapter registers it; backends that do not host the lobby have none.
 * Main thread only.
 */
public interface ILobbyRepaster {

    /** Clear and paste the lobby build again. Returns whether the world was rebuilt. */
    public fun repaste(): Boolean
}
