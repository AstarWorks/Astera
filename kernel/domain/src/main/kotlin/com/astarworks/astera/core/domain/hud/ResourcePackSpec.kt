package com.astarworks.astera.core.domain.hud

import com.astarworks.astera.core.domain.i18n.MessageKey

/**
 * A pushable Minecraft resource pack (Wave 5j, ADR-0097).
 *
 * Wave 6b `PaperHudGateway.pushResourcePack(playerId, spec)` calls
 * `Player.setResourcePack(url, sha1Hash, required, promptComponent)`.
 *
 * @property url HTTP(S) URL the player's client downloads. In Phase 1 PoC
 *   this is a local test-server URL (`http://localhost:8080/...`); Phase 4
 *   Wave 8a sets up CDN hosting with HTTPS.
 * @property sha1Hash 40-character hex SHA-1 of the pack `.zip` content —
 *   client verifies before applying. Case-insensitive.
 * @property required `true` = player kicked if they decline. `false` =
 *   player can decline and see vanilla HUD (graceful degradation).
 * @property promptKey i18n key for the prompt shown to the player
 *   ("Astera uses a custom resource pack for HUD. Apply now?").
 */
public data class ResourcePackSpec(
    public val url: String,
    public val sha1Hash: String,
    public val required: Boolean = false,
    public val promptKey: MessageKey,
) {
    init {
        require(url.startsWith("https://") || url.startsWith("http://")) {
            "ResourcePackSpec.url must be http(s)://, was '$url'"
        }
        require(sha1Hash.matches(SHA1_REGEX)) {
            "ResourcePackSpec.sha1Hash must be 40-character hex, was '$sha1Hash'"
        }
    }

    /**
     * Return a copy with [newKey] replacing [promptKey]. Useful when an
     * operator wants to override only the localization-side wording
     * without re-supplying url/hash/required.
     */
    public fun withPromptKey(newKey: MessageKey): ResourcePackSpec =
        copy(promptKey = newKey)

    public companion object {
        private val SHA1_REGEX = Regex("^[a-fA-F0-9]{40}$")
    }
}
