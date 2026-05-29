package com.astarworks.astera.core.application.config.news

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.news.NewsEntry
import com.astarworks.astera.core.domain.news.NewsId
import com.astarworks.astera.core.domain.news.NewsSeverity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * YAML-shaped DTO for `content/news/<id>.yaml` files
 * (Wave J 5server-content-ops, ADR-0127).
 *
 * Lives in `application/config/` so the domain stays free of `kotlinx.serialization`
 * annotations and the wire schema can evolve independently. Mirrors the
 * pattern set by [com.astarworks.astera.core.application.config.advancement.AdvancementYamlConfig].
 *
 * # Wire format
 *
 * ```yaml
 * id: welcome-to-phase-1
 * title_key: astera.news.welcome-to-phase-1.title
 * body_key: astera.news.welcome-to-phase-1.body
 * published_at: '2026-05-28T00:00:00Z'
 * # expires_at is optional; omit for "never expires"
 * severity: Info  # Info | Important | Critical
 * ```
 */
@Serializable
public data class NewsYamlConfig(
    public val id: String,
    @SerialName("title_key") public val titleKey: String,
    @SerialName("body_key") public val bodyKey: String,
    @SerialName("published_at") public val publishedAt: String,
    @SerialName("expires_at") public val expiresAt: String? = null,
    public val severity: NewsSeverity = NewsSeverity.Info,
) {
    public fun toDomain(): NewsEntry = NewsEntry(
        id = NewsId(id),
        titleKey = MessageKey(titleKey),
        bodyKey = MessageKey(bodyKey),
        publishedAt = Instant.parse(publishedAt),
        expiresAt = expiresAt?.let(Instant::parse),
        severity = severity,
    )
}
