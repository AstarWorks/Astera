package com.astarworks.astera.core.application.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One `content/lobby-npcs/<id>.yaml` file: a clickable NPC placed in the
 * lobby. The loader ([com.astarworks.astera.core.application.service.entity.LobbyNpcLoaderService])
 * spawns it and binds [LobbyNpcInteractionYaml.rightClickIntent] to a right click.
 *
 * ```yaml
 * id: market-npc
 * display_name: astera.lobby.npc.market.name   # i18n key
 * type: display_entity                       # display_entity | armor_stand
 * spawn: { world: lobby, x: -2.5, y: 64.0, z: 0.5, yaw: 90.0, pitch: 0.0 }
 * interaction:
 *   right_click_intent: lobby.shop.open
 * ```
 */
@Serializable
public data class LobbyNpcYamlConfig(
    val id: String,
    @SerialName("display_name") val displayName: String,
    val type: String = TYPE_DISPLAY_ENTITY,
    val spawn: LobbyNpcSpawnYaml,
    val interaction: LobbyNpcInteractionYaml = LobbyNpcInteractionYaml(),
) {
    public companion object {
        public const val TYPE_DISPLAY_ENTITY: String = "display_entity"
        public const val TYPE_ARMOR_STAND: String = "armor_stand"
    }
}

@Serializable
public data class LobbyNpcSpawnYaml(
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float = 0f,
    val pitch: Float = 0f,
)

@Serializable
public data class LobbyNpcInteractionYaml(
    @SerialName("right_click_intent") val rightClickIntent: String? = null,
    @SerialName("left_click_intent") val leftClickIntent: String? = null,
    /** Payload passed with [rightClickIntent] (e.g. `gui.open` + `{ gui: social-hub }`). */
    @SerialName("right_click_payload") val rightClickPayload: Map<String, String> = emptyMap(),
    @SerialName("left_click_payload") val leftClickPayload: Map<String, String> = emptyMap(),
)
