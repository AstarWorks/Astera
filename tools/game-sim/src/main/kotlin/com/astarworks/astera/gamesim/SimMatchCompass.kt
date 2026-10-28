package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.domain.lobby.HotbarLayout
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.application.port.outbound.IMatchCompass
import java.util.concurrent.ConcurrentHashMap

/**
 * [IMatchCompass] for the simulator: records which hotbar slot holds each player's compass and whether it is selected.
 * Like `PaperMatchCompass.onDeath`, a death takes the compass away (the respawn has to give it back).
 */
public class SimMatchCompass : IMatchCompass {
    private val slots: MutableMap<PlayerId, Int> = ConcurrentHashMap()
    private val selected: MutableSet<PlayerId> = ConcurrentHashMap.newKeySet()

    /** Hotbar slot index of [player]'s compass, `null` = none. */
    public fun slotOf(player: PlayerId): Int? = slots[player]

    /** The player scrolls to the compass slot ([on] = true) or to any other slot. */
    public fun select(player: PlayerId, on: Boolean) {
        if (on) selected += player else selected -= player
    }

    /** Subscribes to deaths of the host bus, as the Paper adapter's death handler does. */
    public fun attach(bus: IDomainEventBus) {
        bus.subscribe(PlayerKilled::class) { ev ->
            slots.remove(ev.victim)
            selected -= ev.victim
        }
    }

    override fun give(player: PlayerId) {
        slots[player] = HotbarLayout.MATCH_COMPASS_SLOT
    }

    override fun remove(player: PlayerId) {
        slots.remove(player)
        selected -= player
    }

    override fun isHeld(player: PlayerId): Boolean = player in selected && slots.containsKey(player)
}
