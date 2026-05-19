package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.core.application.port.outbound.IGuiBindingResolver
import com.astarworks.astera.core.application.port.outbound.ILoadoutRepository
import com.astarworks.astera.core.domain.attribute.Attributes
import com.astarworks.astera.core.domain.attribute.IAttributeContainer
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.gui.BindingKey
import com.astarworks.astera.core.domain.gui.ResolvedReactiveSlot
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.LoadoutSlot
import com.astarworks.astera.core.domain.player.PlayerId
import org.slf4j.LoggerFactory

/**
 * Default [IGuiBindingResolver] (Wave 4.5, ADR-0027; Wave 5f RTM #21).
 *
 * # Supported bindings
 *
 * - `player.loadout.<slot>` → [ResolvedReactiveSlot] wrapping the [ItemId]
 *   currently in that loadout slot for [viewer]. Slot tokens correspond to
 *   [LoadoutSlot] entries (lowercase): `main` / `sub` / `move` / `special` /
 *   `armor` / `ultimate`. Returns `null` when the slot is empty / the loadout
 *   is unset.
 *
 * - `player.coins.<currency>` (Wave 5f) → `ResolvedReactiveSlot(itemId =
 *   "indicator-coin-<currency>", countOverride = balance.coerceIn(1, 64),
 *   displayOverride = MessageKey("astera.gui.binding.coins.value"))`. Currency
 *   token is `star` / `gold` / `gem` lowercase. The `countOverride` clamps to
 *   [1, 64] because vanilla ItemStack amounts can't exceed 64 — actual balance
 *   reads happen via the i18n `displayOverride` placeholder rendering, not the
 *   stack count. Returns `null` if the wallet provider is not wired yet
 *   (economy plugin still loading) or the currency token is unknown.
 *
 * - `player.kills` (Wave 5f) → `ResolvedReactiveSlot(itemId = "indicator-kills",
 *   countOverride = kills.coerceIn(1, 64), displayOverride = MessageKey(
 *   "astera.gui.binding.kills.value"))`. Reads [Attributes.STAT_KILLS] via the
 *   per-player [IAttributeContainer]. Returns `null` if the stats provider is
 *   not wired yet.
 *
 * Unknown bindings return `null` and the renderer falls back to a BARRIER
 * placeholder so a typo in `content/gui/` YAML doesn't kill the whole render.
 *
 * # Load order (ADR-0027)
 *
 * Every cross-plugin port is supplied via a lazy provider lambda so resolution
 * happens at GUI render time, after every plugin has finished its `onEnable`.
 * [loadoutsProvider] is published by Weapons; [walletProvider] is published by
 * Economy; [playerStatsProvider] is published by Stats. Each provider may
 * return `null` if the publishing plugin hasn't registered the impl yet — the
 * resolver degrades gracefully (returns `null` for that prefix; other prefixes
 * keep working).
 *
 * Constructor injection only; no Koin annotations (Phase 1 wiring posture).
 */
public class DefaultGuiBindingResolver(
    private val loadoutsProvider: () -> ILoadoutRepository?,
    private val walletProvider: () -> ICurrencyLedger? = { null },
    private val playerStatsProvider: (PlayerId) -> IAttributeContainer? = { null },
) : IGuiBindingResolver {

    /**
     * Eager constructor for tests / call sites where every dependency is
     * already resolved. Pass `null` for collaborators the test doesn't exercise.
     */
    public constructor(
        loadouts: ILoadoutRepository?,
        wallet: ICurrencyLedger? = null,
        stats: ((PlayerId) -> IAttributeContainer?)? = null,
    ) : this(
        loadoutsProvider = { loadouts },
        walletProvider = { wallet },
        playerStatsProvider = stats ?: { null },
    )

    private val log = LoggerFactory.getLogger(DefaultGuiBindingResolver::class.java)

    override fun resolve(binding: BindingKey, viewer: PlayerId?): ResolvedReactiveSlot? {
        if (viewer == null) {
            // Bindings are viewer-relative; without a viewer there is no data.
            return null
        }

        val key = binding.value

        // player.loadout.<slot>
        if (key.startsWith(LOADOUT_PREFIX)) {
            val slotToken = key.removePrefix(LOADOUT_PREFIX)
            val slot = SLOT_BY_TOKEN[slotToken]
            if (slot == null) {
                log.warn(
                    "Unknown loadout slot in binding '{}' (expected one of {})",
                    key,
                    SLOT_BY_TOKEN.keys,
                )
                return null
            }
            val loadouts = loadoutsProvider() ?: run {
                log.debug("Loadout provider not wired yet; binding '{}' returns null.", key)
                return null
            }
            val loadout = loadouts.find(viewer) ?: return null
            val itemId = loadout.itemIn(slot) ?: return null
            return ResolvedReactiveSlot(itemId = itemId)
        }

        // player.coins.<currency>
        if (key.startsWith(COINS_PREFIX)) {
            val currencyToken = key.removePrefix(COINS_PREFIX)
            val currency = CURRENCY_BY_TOKEN[currencyToken]
            if (currency == null) {
                log.warn(
                    "Unknown currency token in binding '{}' (expected one of {})",
                    key,
                    CURRENCY_BY_TOKEN.keys,
                )
                return null
            }
            val ledger = walletProvider() ?: run {
                log.debug("Wallet provider not wired yet; binding '{}' returns null.", key)
                return null
            }
            val balance = ledger.balance(viewer, currency)
            val displayCount = balance.coerceIn(1L, 64L).toInt()
            return ResolvedReactiveSlot(
                itemId = ItemId("indicator-coin-$currencyToken"),
                displayOverride = COINS_DISPLAY_KEY,
                countOverride = displayCount,
            )
        }

        // player.kills
        if (key == KILLS_KEY) {
            val container = playerStatsProvider(viewer) ?: run {
                log.debug("Player stats provider not wired yet; binding '{}' returns null.", key)
                return null
            }
            val kills = container.effectiveNumber(Attributes.STAT_KILLS).toLong()
            val displayCount = kills.coerceIn(1L, 64L).toInt()
            return ResolvedReactiveSlot(
                itemId = ItemId("indicator-kills"),
                displayOverride = KILLS_DISPLAY_KEY,
                countOverride = displayCount,
            )
        }

        log.warn("Binding key '{}' is not supported by DefaultGuiBindingResolver", key)
        return null
    }

    private companion object {
        const val LOADOUT_PREFIX: String = "player.loadout."
        const val COINS_PREFIX: String = "player.coins."
        const val KILLS_KEY: String = "player.kills"

        val COINS_DISPLAY_KEY: MessageKey = MessageKey("astera.gui.binding.coins.value")
        val KILLS_DISPLAY_KEY: MessageKey = MessageKey("astera.gui.binding.kills.value")

        val SLOT_BY_TOKEN: Map<String, LoadoutSlot> =
            LoadoutSlot.entries.associateBy { it.name.lowercase() }

        val CURRENCY_BY_TOKEN: Map<String, Currency> =
            Currency.entries.associateBy { it.name.lowercase() }
    }
}
