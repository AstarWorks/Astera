package com.astarworks.astera.core.adapter.paper.lobby

import com.astarworks.astera.core.application.service.lobby.IToolBayWorld
import org.bukkit.Bukkit

/** Paper [IToolBayWorld] over the lobby world; callers are already on the owning region thread. */
internal class PaperToolBayWorld(private val worldName: String) : IToolBayWorld {
    private fun world() = checkNotNull(Bukkit.getWorld(worldName)) { "lobby world '$worldName' is not loaded" }

    override fun stateAt(x: Int, y: Int, z: Int): String = world().getBlockAt(x, y, z).blockData.asString

    override fun setState(x: Int, y: Int, z: Int, state: String) {
        world().getBlockAt(x, y, z).setBlockData(Bukkit.createBlockData(state), false)
    }
}
