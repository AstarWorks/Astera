package com.astarworks.astera.core.application.service.entity

import com.astarworks.astera.core.application.config.LobbyNpcYamlConfig
import com.astarworks.astera.core.application.contents.EmptyReloadGuard
import com.astarworks.astera.core.application.contents.ReloadableContents
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
import com.astarworks.astera.core.domain.player.PlayerId
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
 *
 * As a reload category (`lobby-npcs`) it diffs by NPC id: removed and changed NPCs are despawned,
 * new and changed ones spawned, unchanged ones keep their handle. A reload with any bad file changes
 * nothing.
 */
public class LobbyNpcLoaderService(
    private val spawner: IEntitySpawner,
    private val interactions: INpcInteractionRouter,
    private val bodies: INpcBodyMaterializer? = null,
    /** Called once per player who had clicked an NPC that a reload just despawned (close what it opened). */
    private val onDespawnedFor: (PlayerId) -> Unit = {},
) : ReloadableContents {
    override val categoryName: String = "lobby-npcs"

    private val loader = ContentLoader(
        parsers = ContentLoader.DEFAULT_PARSERS,
        deserializer = LobbyNpcYamlConfig.serializer(),
    )

    /** Handles of every NPC spawned by [loadFrom], keyed by the YAML `id`. */
    public val spawned: MutableMap<String, EntityHandle> = linkedMapOf()

    /** The config each live NPC was spawned from, for the reload diff. */
    private val specs: MutableMap<String, LobbyNpcYamlConfig> = linkedMapOf()

    public fun loadFrom(dir: Path): ContentLoader.LoadReport = loader.loadFrom(dir) { cfg -> spawn(cfg) }

    override fun reload(dir: Path): ContentLoader.LoadReport {
        val next = linkedMapOf<String, LobbyNpcYamlConfig>()
        val report = loader.loadFrom(dir) { cfg ->
            spawnSpecOf(cfg)
            check(next.put(cfg.id, cfg) == null) { "duplicate lobby NPC id '${cfg.id}'" }
        }
        if (report.failed > 0) return report
        EmptyReloadGuard.check(categoryName, dir, next.size, specs.size)?.let { return it }

        val removed = specs.keys - next.keys
        val changed = next.filter { (id, cfg) -> specs[id]?.let { it != cfg } == true }.keys
        val added = next.keys - specs.keys
        val failures = mutableListOf<ContentLoader.LoadFailure>()
        (removed + changed).forEach { despawn(it) }
        (changed + added).forEach { id ->
            try {
                spawn(next.getValue(id))
            } catch (e: IllegalStateException) {
                failures += ContentLoader.LoadFailure(dir, e.message ?: "spawn failed for '$id'")
            }
        }
        if (failures.isNotEmpty()) return ContentLoader.LoadReport(loaded = 0, failed = failures.size, failures = failures)
        val kept = next.size - changed.size - added.size
        return ContentLoader.LoadReport(
            loaded = next.size,
            failed = 0,
            notes = listOf("lobby NPCs: +${added.size} spawned, -${removed.size} despawned, ~${changed.size} respawned, $kept kept"),
        )
    }

    private fun despawn(id: String) {
        specs.remove(id)
        val handle = spawned.remove(id) ?: return
        val users = interactions.usersOf(handle)
        interactions.unbind(handle)
        bodies?.remove(handle)
        spawner.despawn(handle)
        users.forEach(onDespawnedFor)
    }

    private fun spawnSpecOf(cfg: LobbyNpcYamlConfig): SpawnSpec {
        val nameKey = MessageKey(cfg.displayName)
        return when (cfg.type) {
            LobbyNpcYamlConfig.TYPE_DISPLAY_ENTITY -> SpawnSpec.DisplayNpc(HologramKind.Text(nameKey))
            LobbyNpcYamlConfig.TYPE_ARMOR_STAND -> SpawnSpec.ArmorStandNpc(displayName = nameKey, visible = true)
            else -> error("lobby NPC '${cfg.id}': unsupported type '${cfg.type}' (display_entity | armor_stand)")
        }
    }

    private fun spawn(cfg: LobbyNpcYamlConfig) {
        val nameKey = MessageKey(cfg.displayName)
        val spec = spawnSpecOf(cfg)
        val world = WorldId(cfg.spawn.world)
        val position = Vec3(cfg.spawn.x, cfg.spawn.y, cfg.spawn.z)
        val handle = when (val r = spawner.spawn(world, position, spec)) {
            is Result.Success -> r.value
            is Result.Failure -> error("lobby NPC '${cfg.id}': spawn failed (${r.error})")
        }
        cfg.interaction.rightClickIntent?.let { interactions.bindRightClick(handle, GuiIntent(it, cfg.interaction.rightClickPayload)) }
        cfg.interaction.leftClickIntent?.let { interactions.bindLeftClick(handle, GuiIntent(it, cfg.interaction.leftClickPayload)) }
        bodies?.materialize(handle, world, position, cfg.spawn.yaw, cfg.spawn.pitch, nameKey)
        spawned[cfg.id] = handle
        specs[cfg.id] = cfg
    }
}
