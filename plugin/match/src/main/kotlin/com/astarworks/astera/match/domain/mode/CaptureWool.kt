package com.astarworks.astera.match.domain.mode

import com.astarworks.astera.core.domain.i18n.MessageKey

/**
 * BedWars-style area control: a team must stand in a region for
 * [ticksToCapture] continuous ticks to capture it; first to
 * [capturesToWin] regions wins.
 *
 * `controlRegionIds` lists the stable region identifiers (as defined in the
 * stage YAML) that may be captured during the match.
 */
public data class CaptureWool(
    override val id: GameModeId,
    override val displayNameKey: MessageKey,
    override val descriptionKey: MessageKey,
    val controlRegionIds: List<String>,
    val ticksToCapture: Long = 600L,
    val capturesToWin: Int = 3,
) : GameMode()
