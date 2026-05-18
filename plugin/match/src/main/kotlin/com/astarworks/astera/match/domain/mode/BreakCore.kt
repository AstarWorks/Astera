package com.astarworks.astera.match.domain.mode

import com.astarworks.astera.core.domain.i18n.MessageKey

/**
 * Destroy-the-enemy-core mode (Wave 5c).
 *
 * Each team owns [coresPerTeam] core blocks (block id [coreBlockId]). When all
 * cores of any team are destroyed, the match ends with
 * [com.astarworks.astera.core.domain.event.EndReason.ObjectiveCompleted] and
 * the surviving team wins.
 *
 * Wave 5c lands the mode value + listener with single-core semantics
 * (one BlockBroken of [coreBlockId] ends the match). Per-team core ownership
 * + multi-core counting follow once region metadata is available; see the
 * BreakCoreListener kdoc.
 *
 * @property coreBlockId Namespaced block identifier of the core block (e.g.
 *   `astera:core_block`). Vendor-neutral string; the adapter layer maps to
 *   Bukkit `Material` (ADR-0001 / 0002).
 * @property coresPerTeam Number of cores each team must defend. Defaults to
 *   1 for the Wave 5c PoC.
 */
public data class BreakCore(
    override val id: GameModeId = GameModeId("break-core"),
    override val displayNameKey: MessageKey = MessageKey("astera.match.mode.break-core.name"),
    override val descriptionKey: MessageKey = MessageKey("astera.match.mode.break-core.description"),
    val coreBlockId: String = "astera:core_block",
    val coresPerTeam: Int = 1,
) : GameMode() {
    init {
        require(coreBlockId.isNotBlank()) { "coreBlockId must be non-blank" }
        require(coresPerTeam > 0) { "coresPerTeam must be > 0, got $coresPerTeam" }
    }
}
