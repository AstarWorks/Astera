package com.astarworks.astera.weapons.application.usecase.loadout

import com.astarworks.astera.core.adapter.api.item.IMcItemTemplate
import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.domain.player.Loadout
import com.astarworks.astera.core.domain.player.LoadoutSlot
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.weapons.application.port.outbound.DeliveryResult
import com.astarworks.astera.weapons.application.port.outbound.IWeaponDelivery
import com.astarworks.astera.weapons.application.port.outbound.IWeaponRegistry
import org.slf4j.LoggerFactory

/**
 * On [MatchStarted] every participating player's persisted [Loadout] is
 * resolved and the MAIN slot's weapon item is delivered to their inventory.
 *
 * Wave 4.5 (R3.5) / ADR-0027 scope:
 *
 * - Only the [LoadoutSlot.MAIN] slot is delivered. SUB / MOVE / SPECIAL /
 *   ARMOR / ULTIMATE are reserved for Wave 5+ once the skill effect dispatch
 *   lands — that's where non-MAIN slots map to passive / active capabilities
 *   rather than physical items.
 * - The player roster comes from [IMatchRepository] because [MatchStarted]
 *   only carries the [com.astarworks.astera.core.domain.match.MatchId]. The
 *   same pattern is used by [com.astarworks.astera.economy.application.eventbus.listeners.RewardOnMatchEndedListener]
 *   and is the project-wide convention (silly-knuth §5: events carry the
 *   minimum payload; subscribers ask the repository for richer state).
 * - Weapon lookup goes through [IWeaponRegistry.all] keyed by the loadout
 *   item id, mirroring [com.astarworks.astera.weapons.adapter.WeaponClickListener.handleFire].
 *   When weapons grow large enough that this O(N) scan matters, an
 *   `IWeaponRegistry.findByItemId` method gets added in `plugin/weapons` —
 *   Phase 1 keeps it simple.
 *
 * The listener is constructed in
 * [com.astarworks.astera.weapons.platform.AsteraWeaponsPlugin.onEnable] and
 * subscribed via
 * `eventBus.subscribe(MatchStarted::class) { listener.handle(it) }`.
 *
 * silly-knuth §5: subscriber lives in the Weapons plugin because weapon
 * delivery is Weapons-owned. The Match plugin publishes [MatchStarted] and
 * Weapons reacts; no inverse coupling.
 */
public class MatchStartLoadoutApplyListener(
    private val fetch: LoadoutFetchUseCase,
    private val matchRepository: IMatchRepository,
    private val server: IMcServer,
    private val weaponRegistry: IWeaponRegistry,
    private val delivery: IWeaponDelivery,
    @Suppress("unused")
    private val itemRegistry: IItemRegistry<IMcItemTemplate>,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(event: MatchStarted) {
        val match = matchRepository.findById(event.matchId)
        if (match == null) {
            log.debug("MatchStartLoadoutApplyListener: match {} not found", event.matchId)
            return
        }
        val participants: Set<PlayerId> = match.teams.flatMap { it.members }.toSet()
        if (participants.isEmpty()) {
            log.debug("MatchStartLoadoutApplyListener: match {} has no participants", event.matchId)
            return
        }
        for (playerId in participants) {
            applyFor(playerId)
        }
    }

    private fun applyFor(playerId: PlayerId) {
        val loadout = fetch.execute(playerId)
        val mainItem = loadout.itemIn(LoadoutSlot.MAIN)
        if (mainItem == null) {
            log.debug("MatchStartLoadoutApplyListener: player={} has no MAIN slot", playerId)
            return
        }

        // Resolve the weapon whose itemId matches the MAIN-slot assignment.
        // Mirrors WeaponClickListener.handleFire — O(N) over the weapon
        // registry is fine for Phase 1's weapon catalogue size.
        val weapon = weaponRegistry.all().firstOrNull { it.itemId == mainItem }
        if (weapon == null) {
            log.debug(
                "MatchStartLoadoutApplyListener: no weapon found for itemId={} (player={}) — skipping",
                mainItem, playerId,
            )
            return
        }

        // PlayerId → IMcPlayer via IMcServer. If the player logged off between
        // MatchStarted being published and this lookup we simply skip; the
        // domain doesn't require online presence to publish MatchStarted.
        val target = server.findPlayer(playerId)
        if (target == null) {
            log.debug(
                "MatchStartLoadoutApplyListener: player {} is offline; skipping delivery",
                playerId,
            )
            return
        }

        when (val outcome = delivery.give(target, weapon)) {
            is DeliveryResult.Delivered ->
                log.debug(
                    "MatchStartLoadoutApplyListener: delivered weapon={} to player={}",
                    weapon.id, playerId,
                )
            is DeliveryResult.InventoryFull ->
                log.debug(
                    "MatchStartLoadoutApplyListener: inventory full for player={} weapon={}",
                    playerId, weapon.id,
                )
            is DeliveryResult.UnknownItem ->
                log.debug(
                    "MatchStartLoadoutApplyListener: unknown item key={} for player={} weapon={}",
                    outcome.itemKey, playerId, weapon.id,
                )
        }
    }
}
