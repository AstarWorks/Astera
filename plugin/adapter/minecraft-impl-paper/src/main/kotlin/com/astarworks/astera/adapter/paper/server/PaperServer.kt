package com.astarworks.astera.adapter.paper.server

import com.astarworks.astera.adapter.minecraftapi.entity.IMcPlayer
import com.astarworks.astera.adapter.minecraftapi.server.IMcServer
import com.astarworks.astera.adapter.paper.entity.PaperPlayer
import com.astarworks.astera.domain.model.player.PlayerId
import org.bukkit.Server

class PaperServer(private val bukkit: Server) : IMcServer {

    override fun findPlayer(id: PlayerId): IMcPlayer? =
        bukkit.getPlayer(id.uuid)?.let(::PaperPlayer)

    override fun findPlayerByName(name: String): IMcPlayer? =
        bukkit.getPlayerExact(name)?.let(::PaperPlayer)
}
