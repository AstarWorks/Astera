package com.astarworks.astera.core.application.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `content/gui/kit/lobby-kit.yaml` (ADR-0185): the locked lobby hotbar items and the
 * buttons in the inventory's 2x2 crafting grid. The `kit/` sub-directory keeps the file
 * out of the (non-recursive) GUI spec loader of `content/gui/`.
 *
 * ```yaml
 * entries:
 *   - id: compass
 *     area: hotbar            # hotbar | crafting_grid | crafting_result
 *     slot: 0                 # hotbar 0..8, grid 0..3 (row-major), result 0
 *     item: menu-compass      # an id from content/items/
 *     intent: gui.open        # any GuiIntent key
 *     payload: { gui: main-menu }
 *     lore_key: astera.lobby-kit.hint.use
 * ```
 */
@Serializable
public data class LobbyMenuKitYamlConfig(
    val entries: List<LobbyMenuKitEntryYaml>,
)

@Serializable
public data class LobbyMenuKitEntryYaml(
    val id: String,
    val area: String,
    val slot: Int = 0,
    val item: String,
    val intent: String,
    val payload: Map<String, String> = emptyMap(),
    @SerialName("lore_key") val loreKey: String? = null,
)
