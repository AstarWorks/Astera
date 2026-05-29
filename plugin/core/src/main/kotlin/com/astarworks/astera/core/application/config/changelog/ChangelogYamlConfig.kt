package com.astarworks.astera.core.application.config.changelog

import com.astarworks.astera.core.domain.changelog.ChangelogEntry
import com.astarworks.astera.core.domain.changelog.ChangelogVersion
import com.astarworks.astera.core.domain.i18n.MessageKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * YAML-shaped DTO for `content/changelog/<version>.yaml` files
 * (Wave J 5server-content-ops, ADR-0127).
 *
 * # Wire format
 *
 * ```yaml
 * version: "0.1.0"
 * released_at: '2026-05-28T00:00:00Z'
 * highlights:
 *   - astera.changelog.0_1_0.highlight.1
 *   - astera.changelog.0_1_0.highlight.2
 * full: astera.changelog.0_1_0.full
 * ```
 */
@Serializable
public data class ChangelogYamlConfig(
    public val version: String,
    @SerialName("released_at") public val releasedAt: String,
    public val highlights: List<String>,
    public val full: String,
) {
    public fun toDomain(): ChangelogEntry = ChangelogEntry(
        version = ChangelogVersion(version),
        releasedAt = Instant.parse(releasedAt),
        highlights = highlights.map(::MessageKey),
        full = MessageKey(full),
    )
}
