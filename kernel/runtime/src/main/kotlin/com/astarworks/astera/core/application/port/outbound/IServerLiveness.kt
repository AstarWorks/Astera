package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.proxy.ServerName

/** Which peer backends are alive, from their heartbeats (F3-6). */
public interface IServerLiveness {

    /** True while a heartbeat from [server] arrived within the peer-down window. */
    public fun isUp(server: ServerName): Boolean

    /** Every peer currently up (the local server excluded). */
    public fun upPeers(): Set<ServerName>
}
