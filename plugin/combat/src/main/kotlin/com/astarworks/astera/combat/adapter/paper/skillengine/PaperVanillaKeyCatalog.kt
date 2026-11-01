package com.astarworks.astera.combat.adapter.paper.skillengine

import com.astarworks.astera.core.application.skillengine.load.IVanillaKeyCatalog
import org.bukkit.Material
import org.bukkit.Server

/**
 * Paper [IVanillaKeyCatalog]: the live server decides, so a block state or item key the running Paper would drop at cast time
 * is a load error instead (`createBlockData` for a block state, the item registry for an item key).
 */
public class PaperVanillaKeyCatalog(private val server: Server) : IVanillaKeyCatalog {
    override fun blockProblem(state: String): String? =
        runCatching { server.createBlockData(state) }.fold({ null }, { "server rejects block state '$state': ${it.message}" })

    override fun itemProblem(key: String): String? {
        val material = Material.matchMaterial(key)
        return if (material != null && material.isItem) null else "unknown item id '$key'"
    }
}
