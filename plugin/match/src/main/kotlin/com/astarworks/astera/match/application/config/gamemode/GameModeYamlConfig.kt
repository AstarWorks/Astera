package com.astarworks.astera.match.application.config.gamemode

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.match.domain.mode.GameModeId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * YAML wire shape for `content/game-modes/<id>.yaml` (ADR-0106, Wave 5gamemode).
 *
 * One file per mode. The framework reads these into
 * [com.astarworks.astera.match.application.contents.GameModeContents] which
 * resolves [implClass] against the JVM class loader, instantiates a singleton
 * via its no-arg constructor (the registered class MUST provide one for
 * Phase 1 PoC; constructor injection lands when Koin does, per Wave 5g
 * deferred decisions), and registers it with the framework registry.
 *
 * ### Schema
 *
 * ```yaml
 * id: norma                       # required; lower-kebab-case
 * title_key: astera.gamemode.norma.title
 * impl_class: com.astarworks.astera.match.application.gamemode.NormaGameMode
 * tick_period_ticks: 20           # optional; defaults to 20 (1 sec)
 * default_config:                 # optional; mode-specific overlay (free-form Map for Phase 1)
 *   pillar_count: 3
 *   sudden_death_at_tick: 20000
 * ```
 *
 * ### Why a free-form `default_config` Map?
 *
 * The user idea md proposes typed per-mode config carriers (e.g. a sealed
 * `GameModeConfig` hierarchy). For the Phase 1 PoC foundation we ship the
 * map and let each [com.astarworks.astera.match.domain.gamemode.IGameMode]
 * impl pull what it needs via `defaultConfig[key]`. Typed config carriers
 * land in Phase 3 once Siege + Deathmatch + TDM have informed the cross-mode
 * shape — premature typing here would mean rewriting both the schema and
 * every consumer when the second mode lands.
 *
 * This is consistent with Phase 1 pre-release posture (CLAUDE.md): we don't
 * preserve back-compat, so the eventual typed schema simply replaces this
 * one without a deprecation period.
 *
 * @property id Stable mode id; must match `IGameMode.id()` returned by the
 *   resolved impl. If the two disagree, [toSpec] throws — surfacing the typo
 *   at YAML load rather than at first match start.
 * @property titleKey i18n key for the mode's player-facing title.
 * @property implClass Fully-qualified Kotlin class name of the
 *   [com.astarworks.astera.match.domain.gamemode.IGameMode] impl. Must have a
 *   no-arg constructor.
 * @property tickPeriodTicks Optional override for [IGameMode.tickPeriodTicks];
 *   defaults to 20L (matches the interface default).
 * @property defaultConfig Free-form per-mode config payload (Phase 1 PoC).
 */
@Serializable
public data class GameModeYamlConfig(
    val id: String,
    @SerialName("title_key")
    val titleKey: String,
    @SerialName("impl_class")
    val implClass: String,
    @SerialName("tick_period_ticks")
    val tickPeriodTicks: Long = 20L,
    @SerialName("default_config")
    val defaultConfig: Map<String, String> = emptyMap(),
) {

    /**
     * Convert this YAML row into a parsed [GameModeSpec]. Throws
     * [IllegalArgumentException] on schema violations (invalid id, blank
     * impl class). Catches at the `ContentLoader` layer count as failed
     * loads per the substrate convention.
     */
    public fun toSpec(): GameModeSpec {
        val parsedId = GameModeId(id)
        require(implClass.isNotBlank()) {
            "GameModeYamlConfig.impl_class must not be blank (mode: $id)"
        }
        require(tickPeriodTicks >= 0L) {
            "GameModeYamlConfig.tick_period_ticks must be >= 0 (mode: $id, got $tickPeriodTicks)"
        }
        return GameModeSpec(
            id = parsedId,
            titleKey = MessageKey(titleKey),
            implClass = implClass,
            tickPeriodTicks = tickPeriodTicks,
            defaultConfig = defaultConfig,
        )
    }
}

/**
 * Parsed view of [GameModeYamlConfig] after validation. Owned by the framework
 * registry — consumers receive an [com.astarworks.astera.match.domain.gamemode.IGameMode]
 * once the impl is resolved + instantiated.
 */
public data class GameModeSpec(
    public val id: GameModeId,
    public val titleKey: MessageKey,
    public val implClass: String,
    public val tickPeriodTicks: Long,
    public val defaultConfig: Map<String, String>,
)
