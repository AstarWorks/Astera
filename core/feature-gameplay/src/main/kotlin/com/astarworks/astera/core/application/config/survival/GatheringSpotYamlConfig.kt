package com.astarworks.astera.core.application.config.survival

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.survival.GatheringSkillTrigger
import com.astarworks.astera.core.domain.survival.GatheringSpot
import com.astarworks.astera.core.domain.survival.GatheringSpotId
import com.astarworks.astera.core.domain.survival.GatheringSpotKind
import com.astarworks.astera.core.domain.survival.GatheringYield
import com.astarworks.astera.core.domain.world.WorldId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * YAML-shaped DTO for `content/gathering/<id>.yaml` (Wave 5pve-survival /
 * ADR-0107).
 *
 * Konsist forbids `kotlinx.serialization` annotations in `*.domain.*`, so
 * the DTO lives in `application.config.survival.*`. [toSpec] converts to
 * the domain [GatheringSpot].
 *
 * # Wire format
 *
 * ```yaml
 * id: oak-grove
 * display_name:
 *   en: "Oak Grove"
 *   ja: "オークの森"
 * kind: logging        # fishing | mining | logging | digging
 * trigger: axe_swing   # fishing_rod_cast | pickaxe_swing | axe_swing | shovel_dig
 * world: survival-default
 * position: { x: 100.5, y: 64.0, z: -42.5 }
 * cooldown_ticks: 200  # 10 seconds
 * yields:
 *   - item_id: oak-log
 *     weight: 80
 *     min_count: 1
 *     max_count: 3
 *   - item_id: oak-sapling
 *     weight: 20
 *     min_count: 1
 *     max_count: 1
 * ```
 *
 * # Validation
 *
 * The DTO carries raw strings / numerics; [toSpec] performs domain
 * validation (id regex, kind ↔ trigger compatibility, weights). Any
 * `IllegalArgumentException` is caught by `ContentLoader` and reported as
 * a failed file load — the rest of the registry stays intact.
 */
@Serializable
public data class GatheringSpotYamlConfig(
    public val id: String,
    @SerialName("display_name") public val displayName: Map<String, String> = emptyMap(),
    public val kind: String,
    public val trigger: String,
    public val world: String,
    public val position: PositionYaml,
    @SerialName("cooldown_ticks") public val cooldownTicks: Long,
    public val yields: List<YieldYaml>,
) {

    public fun toSpec(): GatheringSpot {
        val spotId = GatheringSpotId(id)
        val name = LocalizedText(
            byLocale = displayName.entries.associate { (locale, text) ->
                LocaleCode(locale) to text
            },
        )
        val parsedKind = parseKind(kind, spotId)
        val parsedTrigger = parseTrigger(trigger, spotId)
        val parsedYields = yields.map { y ->
            GatheringYield(
                itemId = ItemId(y.itemId),
                weight = y.weight,
                minCount = y.minCount,
                maxCount = y.maxCount,
            )
        }
        return GatheringSpot(
            id = spotId,
            displayName = name,
            kind = parsedKind,
            trigger = parsedTrigger,
            world = WorldId(world),
            position = Vec3(position.x, position.y, position.z),
            yields = parsedYields,
            cooldownTicks = cooldownTicks,
        )
    }

    private fun parseKind(raw: String, spotId: GatheringSpotId): GatheringSpotKind = when (raw.lowercase()) {
        "fishing" -> GatheringSpotKind.Fishing
        "mining" -> GatheringSpotKind.Mining
        "logging" -> GatheringSpotKind.Logging
        "digging" -> GatheringSpotKind.Digging
        else -> throw IllegalArgumentException(
            "GatheringSpotYamlConfig($spotId): unknown kind '$raw'; " +
                "expected one of [fishing, mining, logging, digging]",
        )
    }

    private fun parseTrigger(raw: String, spotId: GatheringSpotId): GatheringSkillTrigger =
        when (raw.lowercase()) {
            "fishing_rod_cast" -> GatheringSkillTrigger.FishingRodCast
            "pickaxe_swing" -> GatheringSkillTrigger.PickaxeSwing
            "axe_swing" -> GatheringSkillTrigger.AxeSwing
            "shovel_dig" -> GatheringSkillTrigger.ShovelDig
            else -> throw IllegalArgumentException(
                "GatheringSpotYamlConfig($spotId): unknown trigger '$raw'; " +
                    "expected one of [fishing_rod_cast, pickaxe_swing, axe_swing, shovel_dig]",
            )
        }
}

@Serializable
public data class PositionYaml(val x: Double, val y: Double, val z: Double)

@Serializable
public data class YieldYaml(
    @SerialName("item_id") val itemId: String,
    val weight: Int,
    @SerialName("min_count") val minCount: Int,
    @SerialName("max_count") val maxCount: Int,
)
