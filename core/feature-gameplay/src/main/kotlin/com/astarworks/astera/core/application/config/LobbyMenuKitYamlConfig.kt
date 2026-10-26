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
 *     area: hotbar            # hotbar | crafting_grid | crafting_result | key_swap | key_sneak_swap | key_drop
 *     slot: 0                 # hotbar 0..8, grid 0..3 (row-major), result 0
 *     item: menu-compass      # an id from content/items/
 *     intent: gui.open        # any GuiIntent key
 *     payload: { gui: main-menu }
 *     lore_key: astera.lobby-kit.hint.use
 *     visible_if: permission:astera.admin.panel   # optional (ADR-0240); replaces the unconditional entry at the same slot
 *     extra_lore:                                  # optional extra lines, each with its own visible_if
 *       - { key: astera.lobby-kit.hint.admin, visible_if: "permission:astera.admin.panel" }
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
    @SerialName("visible_if") val visibleIf: String? = null,
    @SerialName("extra_lore") val extraLore: List<LobbyMenuKitLoreYaml> = emptyList(),
)

/** An extra lore line of a kit entry; `visible_if` as in [LobbyMenuKitEntryYaml] (ADR-0240). */
@Serializable
public data class LobbyMenuKitLoreYaml(
    val key: String,
    @SerialName("visible_if") val visibleIf: String? = null,
)
