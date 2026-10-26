package com.astarworks.astera.core.application.config.mob.pve

import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.mob.MobRegionId
import com.astarworks.astera.core.domain.mob.MobTypeId
import com.astarworks.astera.core.domain.mob.boss.BossId
import com.astarworks.astera.core.domain.mob.encounter.DeathPenaltyRule
import com.astarworks.astera.core.domain.mob.encounter.DifficultyCurve
import com.astarworks.astera.core.domain.mob.encounter.EncounterMobEntry
import com.astarworks.astera.core.domain.mob.encounter.EncounterWave
import com.astarworks.astera.core.domain.mob.encounter.EncounterWaveMob
import com.astarworks.astera.core.domain.mob.encounter.EncounterZone
import com.astarworks.astera.core.domain.mob.encounter.EncounterZoneId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * YAML-shaped DTO for `content/pve/encounters/<id>.yaml`
 * (Wave 5pve-content, ADR-0099).
 *
 * Konsist forbids `kotlinx.serialization` annotations in `*.domain.*`, so
 * this DTO lives in `application.config.mob.pve.*`. [toEntry] returns the
 * `(EncounterZoneId, EncounterZone)` pair that the [EncounterZoneContents]
 * registry consumes.
 *
 * # Wire format
 *
 * ```yaml
 * id: astera:blazing-pit
 * display_name:
 *   en: "Blazing Pit"
 *   ja: "燃え盛る坑道"
 * region: blazing-pit
 * mob_pool:
 *   - mob_type_id: astera:ember-imp
 *     weight: 70
 *   - mob_type_id: astera:flame-jackal
 *     weight: 30
 * max_concurrent: 12
 * difficulty:
 *   type: polynomial
 *   slope: 0.1
 *   pivot: 1
 *   exponent: 1.2
 * boss: astera:scorched-titan
 * death_penalty:
 *   type: item_drop
 *   grace_ticks: 600
 * waves:                    # match-driven encounters only (ADR-0249)
 *   - mobs:
 *       - { mob_type_id: astera:dark-zombie, count: 3 }
 * ```
 *
 * # Difficulty curve discriminator
 *
 * `difficulty.type` selects [DifficultyCurve.Flat] (no extra fields) or
 * [DifficultyCurve.Polynomial] (carries baseLevel / slope / exponent).
 * Omitting `difficulty` defaults to [DifficultyCurve.Flat].
 *
 * # Death penalty discriminator
 *
 * `death_penalty.type` selects [DeathPenaltyRule.SafeDeath] (`safe`, no
 * fields) or [DeathPenaltyRule.ItemDropOnDeath] (`item_drop`, optional
 * `grace_ticks`). Omitting `death_penalty` defaults to safe.
 */
@Serializable
public data class EncounterZoneYamlConfig(
    public val id: String,
    @SerialName("display_name") public val displayName: Map<String, String> = emptyMap(),
    public val region: String,
    @SerialName("mob_pool") public val mobPool: List<EncounterMobEntryYaml> = emptyList(),
    @SerialName("max_concurrent") public val maxConcurrent: Int = 0,
    public val difficulty: DifficultyCurveYaml? = null,
    public val boss: String? = null,
    @SerialName("death_penalty") public val deathPenalty: DeathPenaltyYaml? = null,
    public val waves: List<EncounterWaveYaml> = emptyList(),
) {
    public fun toEntry(): Pair<EncounterZoneId, EncounterZone> {
        val zoneId = EncounterZoneId(id)
        val zone = EncounterZone(
            id = zoneId,
            displayName = LocalizedText(
                byLocale = displayName.entries.associate { (locale, text) ->
                    LocaleCode(locale) to text
                },
            ),
            region = MobRegionId(region),
            mobPool = mobPool.map { it.toDomain(zoneId) },
            maxConcurrent = maxConcurrent,
            difficulty = difficulty?.toDomain(zoneId) ?: DifficultyCurve.Flat,
            boss = boss?.let { BossId(it) },
            deathPenalty = deathPenalty?.toDomain(zoneId) ?: DeathPenaltyRule.SafeDeath,
            waves = waves.map { it.toDomain() },
        )
        return zoneId to zone
    }
}

/** One scripted wave (ADR-0249). */
@Serializable
public data class EncounterWaveYaml(
    public val mobs: List<EncounterWaveMobYaml>,
) {
    public fun toDomain(): EncounterWave = EncounterWave(mobs.map { EncounterWaveMob(MobTypeId(it.mobTypeId), it.count) })
}

@Serializable
public data class EncounterWaveMobYaml(
    @SerialName("mob_type_id") public val mobTypeId: String,
    public val count: Int = 1,
)

@Serializable
public data class EncounterMobEntryYaml(
    @SerialName("mob_type_id") public val mobTypeId: String,
    public val weight: Int,
) {
    public fun toDomain(zoneId: EncounterZoneId): EncounterMobEntry {
        require(mobTypeId.isNotBlank()) {
            "EncounterZoneYamlConfig(${zoneId.value}): mob_pool entry mob_type_id must not be blank"
        }
        return EncounterMobEntry(MobTypeId(mobTypeId), weight)
    }
}

/** Discriminated-union DTO for [DifficultyCurve]. */
@Serializable
public data class DifficultyCurveYaml(
    public val type: String,
    public val slope: Double? = null,
    public val pivot: Int? = null,
    public val exponent: Double? = null,
) {
    public fun toDomain(zoneId: EncounterZoneId): DifficultyCurve {
        return when (type.lowercase()) {
            "flat" -> DifficultyCurve.Flat
            "polynomial" -> DifficultyCurve.Polynomial(
                slope = requireNotNull(slope) {
                    "EncounterZoneYamlConfig(${zoneId.value}).difficulty.polynomial requires slope"
                },
                pivot = pivot ?: 1,
                exponent = exponent ?: 1.0,
            )
            else -> throw IllegalArgumentException(
                "EncounterZoneYamlConfig(${zoneId.value}).difficulty.type='$type' unknown; " +
                    "expected flat / polynomial.",
            )
        }
    }
}

/** Discriminated-union DTO for [DeathPenaltyRule]. */
@Serializable
public data class DeathPenaltyYaml(
    public val type: String,
    @SerialName("grace_ticks") public val graceTicks: Int? = null,
) {
    public fun toDomain(zoneId: EncounterZoneId): DeathPenaltyRule {
        return when (type.lowercase()) {
            "safe", "safe_death" -> DeathPenaltyRule.SafeDeath
            "item_drop", "item_drop_on_death" -> DeathPenaltyRule.ItemDropOnDeath(
                graceTicks = graceTicks ?: DeathPenaltyRule.ItemDropOnDeath.DEFAULT_GRACE_TICKS,
            )
            else -> throw IllegalArgumentException(
                "EncounterZoneYamlConfig(${zoneId.value}).death_penalty.type='$type' unknown; " +
                    "expected safe / item_drop.",
            )
        }
    }
}
