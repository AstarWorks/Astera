package com.astarworks.astera.core.domain.entity

/** Cached profile textures for a packet-only player NPC. No runtime profile lookup. */
public data class NpcSkin(
    public val textureValue: String,
    public val signature: String? = null,
) {
    init { require(textureValue.isNotBlank()) }
}
