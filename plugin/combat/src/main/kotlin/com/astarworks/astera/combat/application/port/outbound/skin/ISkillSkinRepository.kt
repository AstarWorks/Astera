package com.astarworks.astera.combat.application.port.outbound.skin

import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Which skins of a skill a player owns and which one is selected (engine-gaps-v2 2.3). A skin is a `patchKind: skin` variant
 * `<base>@<skin>` of a base skill; at most one skin per (player, base skill) is selected, none selected = the base plays.
 * Ownership and selection live in one row per owned skin, so a selected skin is always an owned one.
 */
public interface ISkillSkinRepository {
    /** The skin the player selected for [baseSkillId], or null = the base. */
    public fun selected(player: PlayerId, baseSkillId: String): String?

    /** The skins the player owns for [baseSkillId]. */
    public fun owned(player: PlayerId, baseSkillId: String): Set<String>

    /** The player now owns [skin] of [baseSkillId] (selected state unchanged; granting twice is a no-op). */
    public fun grant(player: PlayerId, baseSkillId: String, skin: String)

    /** The player no longer owns [skin]; if it was selected the base plays again. */
    public fun revoke(player: PlayerId, baseSkillId: String, skin: String)

    /** Selects [skin] (null = the base). False when [skin] is not owned; nothing changes then. */
    public fun select(player: PlayerId, baseSkillId: String, skin: String?): Boolean
}
