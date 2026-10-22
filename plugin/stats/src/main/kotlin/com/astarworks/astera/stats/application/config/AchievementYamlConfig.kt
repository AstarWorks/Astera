package com.astarworks.astera.stats.application.config

import com.astarworks.astera.core.domain.achievement.AchievementId
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.stats.domain.achievement.Achievement
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** YAML DTO for `content/achievements/<id>.yaml` (ADR-0210): one catalog entry per file. */
@Serializable
public data class AchievementYamlConfig(
    val id: String,
    @SerialName("name_key") val nameKey: String,
    @SerialName("description_key") val descriptionKey: String,
    @SerialName("icon_item_id") val iconItemId: String,
) {
    public fun toDomain(): Achievement = Achievement(
        id = AchievementId(id),
        nameKey = MessageKey(nameKey),
        descriptionKey = MessageKey(descriptionKey),
        iconItemId = iconItemId,
    )
}
