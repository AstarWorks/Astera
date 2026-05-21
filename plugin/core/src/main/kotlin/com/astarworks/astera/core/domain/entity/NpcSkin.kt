package com.astarworks.astera.core.domain.entity

/**
 * Skin material for a Citizens-backed player NPC.
 *
 * Two mutually-exclusive sources:
 *  - [playerName]: resolves a profile via Mojang's API at spawn time. Simpler
 *    but rate-limited and requires online lookup.
 *  - [textureValue] + [signature]: the raw signed property pair from a
 *    cached profile. Allows fully-offline NPC skinning and is the recommended
 *    production path.
 *
 * If [playerName] is null and [textureValue] is also null the NPC keeps
 * Citizens' default Steve skin.
 */
public data class NpcSkin(
    public val playerName: String? = null,
    public val textureValue: String? = null,
    public val signature: String? = null,
) {
    init {
        // signature without textureValue is meaningless
        require(signature == null || textureValue != null) {
            "NpcSkin.signature requires textureValue"
        }
        if (playerName != null) {
            require(playerName.isNotBlank()) { "NpcSkin.playerName must not be blank if set" }
        }
    }
}
