package com.astarworks.astera.core.adapter.paper.event

import com.astarworks.astera.core.adapter.api.event.IMcEvent
import com.astarworks.astera.core.adapter.paper.entity.PaperPlayer
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent

/**
 * Bukkit Listener that translates Paper [PlayerInteractEvent] left-click
 * variants into vendor-neutral [IMcEvent] instances and delegates to a sink.
 *
 * The sink is a plain lambda so application-layer use cases never have to
 * implement Bukkit's `Listener` interface; the items plugin's
 * `ItemSkillTriggerListener` is the canonical consumer.
 *
 * # Renamed from `BukkitEventAdapter` (Wave 6a-6)
 *
 * Pre-Wave-6a-6 this class was named `BukkitEventAdapter` and was the only
 * Paper-side Bukkit-listener bridge in Core. Wave 6a-6 introduces the wider
 * [BukkitEventAdapter] (Role 1 DomainEvent translation + Role 2 defensive
 * cancellation per ADR-0149 §D + ADR-0152). To free the name and keep the
 * single-responsibility narrow, this listener was renamed; the public
 * surface (`(IMcEvent) -> Unit` constructor parameter, `@EventHandler
 * fun onInteract`) is unchanged so the items-side wiring keeps working
 * identically.
 *
 * Forward-only per Phase 1 pre-release posture — no deprecated alias of
 * the old name is kept.
 */
public class PaperClickEventBridge(
    private val sink: (IMcEvent) -> Unit,
) : Listener {

    @EventHandler
    public fun onInteract(e: PlayerInteractEvent) {
        when (e.action) {
            Action.LEFT_CLICK_AIR -> sink(IMcEvent.PlayerLeftClickAir(PaperPlayer(e.player)))
            Action.LEFT_CLICK_BLOCK -> sink(IMcEvent.PlayerLeftClickBlock(PaperPlayer(e.player)))
            else -> Unit
        }
    }
}
