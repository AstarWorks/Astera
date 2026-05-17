package com.astarworks.astera.stats.application.config

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.stats.domain.norma.Norma
import com.astarworks.astera.stats.domain.norma.NormaId
import com.astarworks.astera.stats.domain.norma.NormaReward
import com.astarworks.astera.stats.domain.norma.NormaTarget
import com.astarworks.astera.stats.domain.norma.PeriodKind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * YAML-shaped DTO for `content/norma/` definition files (Wave 2 R1.a).
 *
 * Kept separate from [Norma] so the domain stays free of serialization
 * annotations (Konsist enforces this; see `principles.md` §6 and ADR-0016).
 *
 * Wire format (YAML / TOML / JSON) is decided by `ContentLoader` per ADR-0016;
 * the polymorphic discriminator key is `type` for [NormaTargetYaml] and
 * matches kaml's `PolymorphismStyle.Property` configuration so the same DTO
 * works across formats.
 *
 * `period` accepts the lowercase tokens `daily | weekly | seasonal` and is
 * mapped to the domain [PeriodKind] enum in [toSpec].
 */
@Serializable
public data class NormaYamlConfig(
    val id: String,
    @SerialName("display_name_key") val displayNameKey: String,
    val period: String,
    val target: NormaTargetYaml,
    val reward: NormaRewardYaml,
) {
    /**
     * Materialize this DTO into the domain [Norma].
     *
     * Throws [IllegalArgumentException] if [period] is not a known
     * [PeriodKind] name (case-insensitive).
     */
    public fun toSpec(): Norma {
        val parsedPeriod = parsePeriod(period)
        return Norma(
            id = NormaId(id),
            target = target.toDomain(),
            reward = reward.toDomain(),
            period = parsedPeriod,
            displayKey = MessageKey(displayNameKey),
        )
    }

    private fun parsePeriod(raw: String): PeriodKind {
        // PeriodKind names are PascalCase (Daily / Weekly / Seasonal); we accept
        // any casing so authors can write `period: daily` in YAML idiomatically.
        val normalized = raw.trim().lowercase().replaceFirstChar { it.uppercase() }
        return PeriodKind.values().firstOrNull { it.name == normalized }
            ?: throw IllegalArgumentException(
                "Norma '$id' has unknown period='$raw'. Expected one of: " +
                    PeriodKind.values().joinToString(prefix = "[", postfix = "]") { it.name.lowercase() },
            )
    }
}

/**
 * YAML-shaped DTO for the `reward:` block inside a Norma definition.
 *
 * `currency_id` is nullable: a Norma may grant XP-only, in which case
 * `currency_amount` is ignored (the domain still carries the field, but the
 * Economy plugin's grant logic short-circuits on null).
 */
@Serializable
public data class NormaRewardYaml(
    @SerialName("currency_id") val currencyId: String? = null,
    @SerialName("currency_amount") val currencyAmount: Long = 0L,
    val xp: Long = 0L,
) {
    public fun toDomain(): NormaReward = NormaReward(
        currencyId = currencyId,
        currencyAmount = currencyAmount,
        xp = xp,
    )
}

/**
 * Typed mission-target payload, matching the sealed [NormaTarget] hierarchy.
 *
 * Polymorphic by the `type:` discriminator (kotlinx-JSON convention; kaml's
 * `PolymorphismStyle.Property` reads the same property). Each variant carries
 * only the single counter its kind tracks.
 *
 * Note: `kills` and `match_wins` use a `count` field; `damage` and `xp_gain`
 * use `amount`. This mirrors the domain field names so the YAML reads
 * naturally to anyone familiar with [NormaTarget].
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
@Serializable
public sealed class NormaTargetYaml {

    public abstract fun toDomain(): NormaTarget

    @Serializable
    @SerialName("kills")
    public data class KillsYaml(val count: Long) : NormaTargetYaml() {
        override fun toDomain(): NormaTarget = NormaTarget.Kills(count)
    }

    @Serializable
    @SerialName("damage")
    public data class DamageYaml(val amount: Long) : NormaTargetYaml() {
        override fun toDomain(): NormaTarget = NormaTarget.Damage(amount)
    }

    @Serializable
    @SerialName("match_wins")
    public data class MatchWinsYaml(val count: Long) : NormaTargetYaml() {
        override fun toDomain(): NormaTarget = NormaTarget.MatchWins(count)
    }

    @Serializable
    @SerialName("xp_gain")
    public data class XpGainYaml(val amount: Long) : NormaTargetYaml() {
        override fun toDomain(): NormaTarget = NormaTarget.XpGain(amount)
    }
}
