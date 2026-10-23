package com.astarworks.astera.match.application.config

import com.astarworks.astera.core.application.config.ConfigError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.attribute.AttributeValue
import com.astarworks.astera.core.domain.attribute.SimpleAttributeContainer
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.match.domain.generator.Affiliation
import com.astarworks.astera.match.domain.generator.GeneratorAttributes
import com.astarworks.astera.match.domain.generator.GeneratorId
import com.astarworks.astera.match.domain.generator.GeneratorTier
import com.astarworks.astera.match.domain.generator.StarGenerator
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.seconds

/**
 * YAML-shaped DTO for `content/generators/` definition files (ADR-0003 / ADR-0016).
 *
 * Lives in `application/config/` so the domain ([StarGenerator]) stays free of
 * `kotlinx.serialization` annotations. The DTO maps 1:1 to the on-disk YAML;
 * [toDomain] performs the kebab-case / enum / tier validation that surfaces
 * authoring mistakes at load time, not at first spawn.
 *
 * # Wire format
 *
 * ```yaml
 * id: arena-center
 * match_stage_id: example-arena
 * position: { x: 0.0, y: 64.0, z: 0.0 }
 * period_seconds: 30.0
 * amount: 1
 * tier: iron
 * affiliation:
 *   kind: neutral
 * ```
 *
 * For a team-owned generator:
 *
 * ```yaml
 * affiliation:
 *   kind: team
 *   team_id: red
 * ```
 *
 * # Wave 5c scope
 *
 * - `match_stage_id` is the **stage** the generator belongs to. At match-start
 *   time a use case (Phase C wiring) instantiates the generator under the
 *   active `MatchId` for that stage.
 * - The `period` field is expressed in **seconds** (not ticks) on the wire so
 *   authors do not need to do mental tick math; the conversion happens here.
 * - `tier` is a lower-kebab string for symmetry with the rest of Astera's
 *   YAML authoring style ("iron" / "gold" / "emerald" / "diamond").
 */
@Serializable
public data class GeneratorYamlConfig(
    public val id: String,
    @SerialName("match_stage_id") public val matchStageId: String,
    public val position: Vec3YamlConfig,
    @SerialName("period_seconds") public val periodSeconds: Double,
    public val amount: Int,
    public val tier: String,
    public val affiliation: AffiliationYamlConfig,
    @SerialName("stockpile_cap") public val stockpileCap: Int = StarGenerator.DEFAULT_STOCKPILE_CAP,
    @SerialName("pickup_radius") public val pickupRadius: Double = StarGenerator.DEFAULT_PICKUP_RADIUS,
) {
    /**
     * Convert the parsed DTO into a domain [StarGenerator] under [matchId].
     *
     * A fresh [SimpleAttributeContainer] is attached so per-instance runtime
     * state (energy, modifiers) has somewhere to live without callers needing
     * to remember to build one. Anomalies / Sudden Death overlay modifiers
     * on this container at match runtime.
     *
     * `PERIOD_MULTIPLIER` and `AMOUNT_MULTIPLIER` are pre-seeded to `1.0`
     * (identity) — required by ADR-0058 §1 modifier semantics. Without a
     * non-zero base, a MULTIPLY modifier compounds against `0.0` and is
     * silently no-op (see `AdvanceGeneratorChargeUseCase` kdoc + the
     * matching seed in the test fixtures).
     *
     * Returns `Result.Failure(ConfigError)` on any kebab / enum / tier
     * violation per the `coding-posture.md` "no raw JVM throwables in
     * application API" rule. The `id` / `team_id` value-class constructors
     * still surface their lexical violations as `IllegalArgumentException`
     * — those are domain-layer invariants, intentionally not normalized to
     * `ConfigError` here.
     */
    public fun toDomain(matchId: MatchId): Result<StarGenerator, ConfigError> {
        val parsedTier = parseTier(tier)
            .onFailure { return Result.failure(it) }
            .let { (it as Result.Success).value }

        val parsedAffiliation = affiliation.toDomain()
            .onFailure { return Result.failure(it) }
            .let { (it as Result.Success).value }

        val attributes = SimpleAttributeContainer()
        attributes.set(GeneratorAttributes.PERIOD_MULTIPLIER, AttributeValue.NumberValue(1.0))
        attributes.set(GeneratorAttributes.AMOUNT_MULTIPLIER, AttributeValue.NumberValue(1.0))

        return Result.success(
            StarGenerator(
                id = GeneratorId(id),
                matchId = matchId,
                position = position.toVec3(),
                period = periodSeconds.seconds,
                amount = amount,
                tier = parsedTier,
                affiliation = parsedAffiliation,
                attributes = attributes,
                stockpileCap = stockpileCap,
                pickupRadius = pickupRadius,
            ),
        )
    }

    private fun parseTier(raw: String): Result<GeneratorTier, ConfigError> = when (raw.lowercase()) {
        "iron" -> Result.success(GeneratorTier.Iron)
        "gold" -> Result.success(GeneratorTier.Gold)
        "emerald" -> Result.success(GeneratorTier.Emerald)
        "diamond" -> Result.success(GeneratorTier.Diamond)
        else -> Result.failure(
            ConfigError.UnknownEnumValue(
                field = "tier",
                rawValue = raw,
                accepted = listOf("iron", "gold", "emerald", "diamond"),
            ),
        )
    }
}

/**
 * Polymorphic-by-`kind` YAML DTO for [Affiliation]. The flat shape (rather
 * than kotlinx's sealed-polymorphism) is intentional: it keeps the on-disk
 * YAML stable even if the sealed hierarchy gains a third variant later.
 *
 * Authoring rules:
 *
 * - `kind: neutral` — `team_id` MUST be absent (a present value is rejected
 *   to surface authoring mistakes).
 * - `kind: team` — `team_id` is required.
 */
@Serializable
public data class AffiliationYamlConfig(
    public val kind: String,
    @SerialName("team_id") public val teamId: String? = null,
) {
    public fun toDomain(): Result<Affiliation, ConfigError> = when (kind.lowercase()) {
        "neutral" -> {
            if (teamId != null) {
                Result.failure(
                    ConfigError.FieldShapeMismatch(
                        field = "affiliation.team_id",
                        detailMessage = "kind=neutral must not carry team_id (got '$teamId')",
                    ),
                )
            } else {
                Result.success(Affiliation.Neutral)
            }
        }
        "team" -> {
            if (teamId == null) {
                Result.failure(
                    ConfigError.FieldShapeMismatch(
                        field = "affiliation.team_id",
                        detailMessage = "kind=team requires team_id",
                    ),
                )
            } else {
                Result.success(Affiliation.Team(TeamId(teamId)))
            }
        }
        else -> Result.failure(
            ConfigError.UnknownEnumValue(
                field = "affiliation.kind",
                rawValue = kind,
                accepted = listOf("neutral", "team"),
            ),
        )
    }
}
