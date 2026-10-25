package com.astarworks.astera.core.application.config.cosmetic

import com.astarworks.astera.core.domain.cosmetic.Cosmetic
import com.astarworks.astera.core.domain.cosmetic.CosmeticKind
import com.astarworks.astera.core.domain.i18n.MessageKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * YAML DTO for `content/cosmetics/<id>.yaml`, one cosmetic per file.
 *
 * ```yaml
 * id: crimson
 * kind: name_color        # title | name_color | kill_effect
 * name_key: astera.cosmetic.name_color.crimson
 * price_gold: 300         # omit = granted only (level / achievement), never sold
 * value: "#DC143C"        # name_color: colour; kill_effect: particle key; title: unused
 * count: 12               # kill_effect: particle count
 * ```
 */
@Serializable
public data class CosmeticYamlConfig(
    val id: String,
    val kind: String,
    @SerialName("name_key") val nameKey: String,
    @SerialName("price_gold") val priceGold: Long? = null,
    val value: String = "",
    val count: Int = 12,
) {
    public fun toDomain(): Cosmetic = Cosmetic(
        id = id,
        kind = CosmeticKind.valueOf(kind.trim().uppercase()),
        nameKey = MessageKey(nameKey),
        priceGold = priceGold,
        value = value,
        count = count,
    )
}
