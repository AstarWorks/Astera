package com.astarworks.astera.core.domain.hud

/**
 * The styling of one player's name as seen by one viewer (ADR-0200).
 *
 * @property group stable id of the team the name is grouped under; groups sort
 *   alphabetically in the tab list, so one team's players sit together.
 * @property colorName a MiniMessage / Adventure named colour (`red`, `dark_aqua`, ...);
 *   unknown names render white.
 * @property hp whole HP shown under the nameplate, or `null` for none.
 */
public data class PlayerLabel(
    public val group: String,
    public val colorName: String,
    public val hp: Int? = null,
) {
    init {
        require(group.isNotBlank()) { "PlayerLabel.group must not be blank" }
        require(hp == null || hp >= 0) { "PlayerLabel.hp must be >= 0: $hp" }
    }
}
