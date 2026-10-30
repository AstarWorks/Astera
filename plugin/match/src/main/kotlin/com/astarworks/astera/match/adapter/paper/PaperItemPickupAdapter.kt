package com.astarworks.astera.match.adapter.paper

import com.astarworks.astera.core.adapter.paper.wiring.OwnedListeners
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.domain.event.EntityPickedUpItem
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.slf4j.LoggerFactory

/**
 * Match-side Paper → DomainEvent adapter that closes the
 * ADR-0134 deferred-events allowlist entry `match.EntityPickedUpItem`
 * (Wave 6a-N3, ADR-0159).
 *
 * # Why this lives in `:plugin:match` and not in Core
 *
 * Core's [com.astarworks.astera.core.adapter.paper.event.BukkitEventAdapter]
 * (Wave 6a-6, ADR-0153) owns the Bukkit → Core-DomainEvent translation
 * surface, but [EntityPickedUpItem] is a Match-plugin domain event and
 * ADR-0022 sibling-isolation forbids Core from importing
 * `com.astarworks.astera.match.*`. The Core adapter therefore keeps an
 * intentional no-op anchor on `EntityPickupItemEvent`; this class is the
 * sibling translator that actually performs the publish.
 *
 * # Behaviour
 *
 *  - Listener priority is `MONITOR` with `ignoreCancelled = true`: pickup
 *    side-effects (objective tracking) should only fire when the pickup
 *    actually happens, after other plugins have had their say.
 *  - Non-player pickups (mobs grabbing armour off the ground, etc.) are
 *    ignored — the domain event is player-scoped.
 *  - Players not currently in a match return early (
 *    `IMatchRepository.findMatchOf(playerId) == null`); the sidebar
 *    subscriber is a match-scoped consumer and an out-of-match
 *    publish would just be silently dropped further down anyway.
 *  - `itemId` synthesis: prefer the Astera-canonical `astera:item_id` PDC
 *    string written by `PaperItemMaterializer` (Wave _items-1, ADR-0073)
 *    so objectives that key on Astera content ids (`astera:tnt_carry`)
 *    see the same id used elsewhere. When the PDC is absent (vanilla
 *    drop) fall back to `bukkit:<lower-kebab material name>` mirroring
 *    the same convention Core's `bukkitEntityTypeToMobTypeId` uses for
 *    `MobKilled`.
 *  - `EntityPickedUpItem.itemId` is a raw `String` field (see the
 *    domain event KDoc); we therefore do not run [ItemId] validation on
 *    the fallback path — a future schema tightening can move the validation
 *    here without changing the Bukkit-listener wiring.
 *
 * # PDC key duplication note
 *
 * The `NamespacedKey("astera", "item_id")` literal duplicates
 * `PaperItemIdResolver.ITEM_ID_KEY` from `:plugin:items`. We do not import
 * the resolver because ADR-0022's sibling matrix (`Match ← Core, Combat`)
 * forbids the `match → items` direction. The duplication is intentional
 * scaffolding; if a third plugin needs the same PDC contract we will
 * promote the key constant to Core (a vendor-neutral abstraction over
 * "Astera-owned item identity") rather than crack the sibling matrix.
 *
 * # Threading + exception safety
 *
 * Bukkit pickup events dispatch on the main thread; this adapter runs
 * synchronously and does no async work. The publish is wrapped in a
 * try/catch so a faulty subscriber cannot propagate back into the
 * Bukkit dispatch loop — pickup is a high-frequency event class in
 * Phase 1 PoC traffic (once per few seconds during active play) and a
 * single dropped translation is strictly cheaper than a per-tick crash
 * loop that would disable the plugin.
 *
 * # Registration
 *
 * Construct in `AsteraMatchPlugin.onEnable` and call [start] with the
 * plugin instance. The Bukkit listener side is unregistered by Paper's
 * normal `HandlerList.unregisterAll(plugin)` teardown — no manual
 * handle bookkeeping required.
 */
public class PaperItemPickupAdapter(
    private val matchRepository: IMatchRepository,
    private val eventBus: IDomainEventBus,
    private val scheduler: IScheduler,
) : Listener {

    private val log = LoggerFactory.getLogger(javaClass)

    private var asteraItemIdKey: NamespacedKey? = null

    /**
     * Register the Bukkit listener and cache the `astera:item_id`
     * [NamespacedKey] so per-pickup translation does not allocate.
     */
    public fun start(plugin: JavaPlugin, listeners: OwnedListeners) {
        asteraItemIdKey = NamespacedKey(plugin, ASTERA_ITEM_ID_KEY)
        listeners.register(this)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public fun onEntityPickupItem(event: EntityPickupItemEvent) {
        val player = event.entity as? Player ?: return
        val playerId = PlayerId(player.uniqueId)
        val match = matchRepository.findMatchOf(playerId) ?: return
        try {
            val stack = event.item.itemStack
            eventBus.publish(
                EntityPickedUpItem(
                    matchId = match.id,
                    playerId = playerId,
                    itemId = synthesizeItemId(stack),
                    quantity = stack.amount,
                    atTick = scheduler.currentTick(),
                ),
            )
        } catch (ex: RuntimeException) {
            log.warn(
                "PaperItemPickupAdapter.onEntityPickupItem: publish failed for player={} match={}; event dropped",
                playerId,
                match.id,
                ex,
            )
        }
    }

    /**
     * Returns the Astera-canonical item id stored in the stack's PDC by
     * `PaperItemMaterializer` (Wave _items-1, ADR-0073) when present, else
     * the `bukkit:<lower-kebab material name>` fallback so subscribers can
     * still observe vanilla-drop pickups for ambient objectives.
     */
    private fun synthesizeItemId(stack: ItemStack): String {
        val key = asteraItemIdKey
        if (key != null) {
            val meta = stack.itemMeta
            if (meta != null) {
                val asteraId = meta.persistentDataContainer.get(key, PersistentDataType.STRING)
                if (!asteraId.isNullOrBlank()) return asteraId
            }
        }
        return "bukkit:${stack.type.name.lowercase().replace('_', '-')}"
    }

    public companion object {
        /**
         * PDC key (under the `astera` namespace) carrying the Astera
         * content item id on a materialised stack. Mirrors
         * `com.astarworks.astera.items.adapter.paper.PaperItemIdResolver.ITEM_ID_KEY`
         * — duplicated rather than imported because ADR-0022 sibling-isolation
         * forbids the `match → items` direction. Promote to Core if a third
         * plugin ever needs the same contract.
         */
        public const val ASTERA_ITEM_ID_KEY: String = "item_id"
    }
}
