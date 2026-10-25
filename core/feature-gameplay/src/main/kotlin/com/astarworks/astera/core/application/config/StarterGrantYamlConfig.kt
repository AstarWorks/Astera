package com.astarworks.astera.core.application.config

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.StarterGrant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * YAML-shaped DTO for `content/balance/starter-grant.yaml` (Wave 5e-fix).
 *
 * Lives in `application/config/` so [StarterGrant] (domain) stays free of
 * `kotlinx.serialization` annotations (per `coding-posture.md` + the
 * `ArchitectureTest` Konsist rule that forbids `kotlinx.serialization` in
 * `*.domain.*`).
 *
 * # Wire format
 *
 * ```yaml
 * starter_items:
 *   - example-sword
 *   - example-wand
 *
 * wallet_grants:
 *   GOLD: 100
 *   GEM: 0
 *
 * tutorial:
 *   auto_enroll: true
 *   script_id: getting-started
 *
 * welcome_dialog_id: astera-welcome
 * ```
 *
 * # Validation
 *
 * - Wallet currency keys must match a [Currency] enum constant (case-insensitive)
 *   → [ConfigError.UnknownEnumValue].
 * - Wallet amounts must be `>= 0` → [ConfigError.OutOfRange].
 * - When `tutorial.auto_enroll = true`, `tutorial.script_id` must be set →
 *   [ConfigError.FieldShapeMismatch].
 *
 * The loader's [Companion.parse] swallows missing file → [StarterGrant.EMPTY]
 * so a Phase 1 deployment without an authored YAML still boots; the
 * `AsteraCorePlugin` log line tells the operator what was loaded.
 */
@Serializable
public data class StarterGrantYamlConfig(
    @SerialName("starter_items") public val starterItems: List<String> = emptyList(),
    @SerialName("wallet_grants") public val walletGrants: Map<String, Long> = emptyMap(),
    public val tutorial: TutorialBlock = TutorialBlock(),
    @SerialName("welcome_dialog_id") public val welcomeDialogId: String? = null,
) {
    @Serializable
    public data class TutorialBlock(
        @SerialName("auto_enroll") public val autoEnroll: Boolean = false,
        @SerialName("script_id") public val scriptId: String? = null,
    )

    /**
     * Convert the parsed DTO to the domain [StarterGrant].
     *
     * Per `coding-posture.md` §"Application API surface" returns
     * `Result.Failure(ConfigError)` rather than throwing. First-failure
     * short-circuit (consistent with `BlockDurabilityYamlConfig.toDomain`).
     */
    public fun toDomain(): Result<StarterGrant, ConfigError> {
        // 1. Wallet grants — parse currency enum + range-check amount.
        val parsedWallet = mutableMapOf<Currency, Long>()
        for ((rawKey, amount) in walletGrants) {
            val currency = parseCurrency(rawKey, "wallet_grants.$rawKey")
            when (currency) {
                is Result.Success -> {
                    if (amount < 0) {
                        return Result.failure(
                            ConfigError.OutOfRange(
                                field = "wallet_grants.$rawKey",
                                rawValue = amount.toString(),
                                constraint = "must be >= 0",
                            ),
                        )
                    }
                    if (amount > 0) parsedWallet[currency.value] = amount
                }
                is Result.Failure -> return Result.failure(currency.error)
            }
        }

        // 2. Tutorial block — when auto_enroll is true, script_id is required.
        if (tutorial.autoEnroll && tutorial.scriptId.isNullOrBlank()) {
            return Result.failure(
                ConfigError.FieldShapeMismatch(
                    field = "tutorial.script_id",
                    detailMessage = "must be set when tutorial.auto_enroll = true",
                ),
            )
        }

        // 3. Item ids — validate lexical shape now so YAML typos surface at
        //    load time rather than first-join time. Registry existence is
        //    not enforced here (a starter item might be content-pack-pending).
        val parsedItems = starterItems.mapIndexed { idx, raw ->
            try {
                ItemId(raw)
            } catch (e: IllegalArgumentException) {
                return Result.failure(
                    ConfigError.InvalidIdentifier(
                        field = "starter_items[$idx]",
                        rawValue = raw,
                        constraint = e.message ?: "kebab-case [a-z0-9-]",
                    ),
                )
            }
        }

        return Result.success(
            StarterGrant(
                starterItems = parsedItems,
                walletGrants = parsedWallet.toMap(),
                tutorialAutoEnroll = tutorial.autoEnroll,
                tutorialScriptId = tutorial.scriptId?.takeIf { tutorial.autoEnroll },
                welcomeDialogId = welcomeDialogId?.takeIf { it.isNotBlank() },
            ),
        )
    }

    private fun parseCurrency(raw: String, field: String): Result<Currency, ConfigError> =
        runCatching { Currency.valueOf(raw.uppercase()) }.fold(
            onSuccess = { Result.success(it) },
            onFailure = {
                Result.failure(
                    ConfigError.UnknownEnumValue(
                        field = field,
                        rawValue = raw,
                        accepted = Currency.entries.map { it.name },
                    ),
                )
            },
        )
}
