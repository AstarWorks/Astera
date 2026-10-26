package com.astarworks.astera.core.domain.hud

/**
 * The styling of one player's name as seen by one viewer (ADR-0206).
 *
 * @property group stable id of the team the name is grouped under; groups sort
 *   alphabetically in the tab list, so one team's players sit together.
 * @property colorName a MiniMessage / Adventure named colour (`red`, `dark_aqua`, ...);
 *   unknown names render white.
 * @property hp whole HP shown under the nameplate, or `null` for none.
 * @property concealed the viewer must not learn who this is or where: no nameplate
 *   (so none through walls) and no tab-list row. The entity itself stays visible in the
 *   world; hiding it would make enemies untargetable. Used for the opposing team.
 */
public data class PlayerLabel(
    public val group: String,
    public val colorName: String,
    public val hp: Int? = null,
    public val concealed: Boolean = false,
) {
    init {
        require(group.isNotBlank()) { "PlayerLabel.group must not be blank" }
        require(hp == null || hp >= 0) { "PlayerLabel.hp must be >= 0: $hp" }
    }
}
