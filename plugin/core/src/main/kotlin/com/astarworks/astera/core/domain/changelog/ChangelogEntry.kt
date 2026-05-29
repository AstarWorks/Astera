package com.astarworks.astera.core.domain.changelog

import com.astarworks.astera.core.domain.i18n.MessageKey
import java.time.Instant

/**
 * Release-notes entry for a single Astera version
 * (Wave J 5server-content-ops, ADR-0127).
 *
 * Loaded from `content/changelog/<version>.yaml` files at boot. Highlights are
 * short bullet keys rendered as one chat line each by `/astera-patch`; `full`
 * is the long-form message rendered when the player runs `/astera-patch <version>`
 * for detail.
 */
public data class ChangelogEntry(
    public val version: ChangelogVersion,
    public val releasedAt: Instant,
    public val highlights: List<MessageKey>,
    public val full: MessageKey,
) {
    init {
        require(highlights.isNotEmpty()) {
            "ChangelogEntry $version: highlights must not be empty"
        }
    }
}
