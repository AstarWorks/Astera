package com.astarworks.astera.core.adapter.paper.server

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.adapter.paper.entity.PaperPlayer
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.domain.player.PlayerId
import org.bukkit.Server

class PaperServer(
    private val bukkit: Server,
    private val renderer: () -> IMessageRenderer? = { null },
) : IMcServer {

    override fun findPlayer(id: PlayerId): IMcPlayer? =
        bukkit.getPlayer(id.uuid)?.let { PaperPlayer(it, renderer()) }

    override fun findPlayerByName(name: String): IMcPlayer? =
        bukkit.getPlayerExact(name)?.let { PaperPlayer(it, renderer()) }
}
