package com.astarworks.astera.core.application.service.entity

import com.astarworks.astera.core.application.config.LobbyNpcYamlConfig
import com.astarworks.astera.core.application.port.outbound.entity.IEntitySpawner
import com.astarworks.astera.core.application.port.outbound.entity.INpcBodyMaterializer
import com.astarworks.astera.core.application.port.outbound.entity.INpcInteractionRouter
import com.astarworks.astera.core.application.service.ContentLoader
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.entity.HologramKind
import com.astarworks.astera.core.domain.entity.SpawnSpec
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.world.WorldId
import java.nio.file.Path

/**
 * Spawns every NPC declared under `content/lobby-npcs/` (ADR-0119 content,
 * ADR-0082 spawner) and binds its click intents.
 *
 * Per file: [IEntitySpawner.spawn] mints the logical NPC, the YAML's
 * `right_click_intent` / `left_click_intent` are bound on
 * [INpcInteractionRouter], and (when the host provides one) [bodies] gives the
 * NPC a physical body. A click on that body dispatches the bound
 * [GuiIntent], e.g. `lobby.shop.open` opens the shop.
 *
 * One bad file is reported in [LoadReport.failures] and skipped; the rest still load.
 */
public class LobbyNpcLoaderService(
    private val spawner: IEntitySpawner,
    private val interactions: INpcInteractionRouter,
    private val bodies: INpcBodyMaterializer? = null,
) {
    private val loader = ContentLoader(
        parsers = ContentLoader.DEFAULT_PARSERS,
        deserializer = LobbyNpcYamlConfig.serializer(),
    )

    /** Handles of every NPC spawned by [loadFrom], keyed by the YAML `id`. */
    public val spawned: MutableMap<String, EntityHandle> = linkedMapOf()

    public fun loadFrom(dir: Path): ContentLoader.LoadReport = loader.loadFrom(dir) { cfg -> spawn(cfg) }

    private fun spawn(cfg: LobbyNpcYamlConfig) {
        val nameKey = MessageKey(cfg.displayName)
        val spec = when (cfg.type) {
            LobbyNpcYamlConfig.TYPE_DISPLAY_ENTITY -> SpawnSpec.DisplayNpc(HologramKind.Text(nameKey))
            LobbyNpcYamlConfig.TYPE_ARMOR_STAND -> SpawnSpec.ArmorStandNpc(displayName = nameKey, visible = true)
            else -> error("lobby NPC '${cfg.id}': unsupported type '${cfg.type}' (display_entity | armor_stand)")
        }
        val world = WorldId(cfg.spawn.world)
        val position = Vec3(cfg.spawn.x, cfg.spawn.y, cfg.spawn.z)
        val handle = when (val r = spawner.spawn(world, position, spec)) {
            is Result.Success -> r.value
            is Result.Failure -> error("lobby NPC '${cfg.id}': spawn failed (${r.error})")
        }
        cfg.interaction.rightClickIntent?.let { interactions.bindRightClick(handle, GuiIntent(it)) }
        cfg.interaction.leftClickIntent?.let { interactions.bindLeftClick(handle, GuiIntent(it)) }
        bodies?.materialize(handle, world, position, cfg.spawn.yaw, cfg.spawn.pitch, nameKey)
        spawned[cfg.id] = handle
    }
}
