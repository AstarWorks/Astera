package com.astarworks.astera.match.application.config

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.match.domain.loadout.BotWeaponFamily
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * YAML DTO for `content/bot-loadouts/loadouts.yaml`: the weapon pool bots draw their match loadout from. Strict: an
 * unknown key, an empty prefix or a duplicate item id fails the load (unknown item ids / items without skill bindings are
 * rejected by `BotLoadoutService.validate` once the item registry is known).
 *
 * ```yaml
 * bot_name_prefix: "Bot_"     # accounts of the bot runner; the only signal the game server has that a player is a bot
 * seed: 7                      # optional; fixed draws for a reproducible run (default: derived from the match id)
 * ranged: [pistol, rifle]      # guns and bows
 * melee: [knight-sword]        # swords, axes, hammers
 * caster: [fire-staff]         # staves and casters
 * ```
 */
@Serializable
public data class BotLoadoutYamlConfig(
    @SerialName("bot_name_prefix") public val botNamePrefix: String = "Bot_",
    public val seed: Long? = null,
    public val ranged: List<String> = emptyList(),
    public val melee: List<String> = emptyList(),
    public val caster: List<String> = emptyList(),
) {
    public fun toPool(): BotLoadoutPool {
        require(botNamePrefix.isNotBlank()) { "bot_name_prefix must not be blank" }
        val families = mapOf(
            BotWeaponFamily.RANGED to ranged.map(::ItemId),
            BotWeaponFamily.MELEE to melee.map(::ItemId),
            BotWeaponFamily.CASTER to caster.map(::ItemId),
        )
        val all = families.values.flatten()
        val duplicates = all.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        require(duplicates.isEmpty()) { "bot loadout pool lists an item twice: ${duplicates.joinToString { it.value }}" }
        require(all.isNotEmpty()) { "bot loadout pool is empty" }
        return BotLoadoutPool(botNamePrefix, seed, families)
    }
}

/** The decoded pool: [families] lists candidate item ids per family, not yet checked against the item registry. */
public data class BotLoadoutPool(
    public val botNamePrefix: String,
    public val seed: Long?,
    public val families: Map<BotWeaponFamily, List<ItemId>>,
)
