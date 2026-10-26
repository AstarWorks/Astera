package com.astarworks.astera.core.adapter.paper.gui

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.application.gui.GuiDiffEngine
import com.astarworks.astera.core.application.gui.SlotPatch
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.gui.GuiSlot
import com.astarworks.astera.core.domain.gui.GuiSpec
import com.astarworks.astera.core.domain.player.PlayerId
import net.kyori.adventure.text.minimessage.MiniMessage
import com.astarworks.astera.core.adapter.paper.text.AsteraTextDeserializer
import org.bukkit.Bukkit
import org.bukkit.inventory.Inventory
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Paper-side concrete of [IGuiAdapter] (ADR-0021, Wave 4 R3 Declarative GUI).
 *
 * Owns the "last spec each online player saw" map and exposes a four-method
 * surface: open / update / close / currentSpec. Update runs the spec through
 * [GuiDiffEngine] and applies the resulting [SlotPatch]es directly against the
 * Bukkit `Inventory` so that **only changed cells round-trip** — no full
 * inventory rebuild every state tick. Title changes degrade to a close +
 * full-redraw because vanilla Minecraft does not support live title mutation.
 *
 * ### Threading
 *
 * Mutations dispatch to the viewer's Entity scope; the adapter
 * mutates Bukkit `Inventory` instances owned by that player. Sessions
 * are stored in a [ConcurrentHashMap] only so the [PaperGuiListener]'s
 * `onQuit` handler (also on the player's Entity scope, but in a different event-handler
 * frame) can safely `forgetSession` without locking.
 *
 * ### Reopen suppression
 *
 * Adapter-driven `closeInventory()` calls (forced by title change or by
 * `open()` replacing an existing session) emit an `InventoryCloseEvent` the
 * [PaperGuiListener] would otherwise interpret as "the player closed the
 * GUI". The [reopening] set marks players currently mid-reopen so the
 * listener skips its forget step for that one event.
 */
public class PaperGuiAdapter(
    @Suppress("unused") private val server: IMcServer,
    private val scheduler: IScheduler,
    private val renderer: PaperItemTemplateRenderer,
    private val messages: IMessageRenderer,
) : IGuiAdapter {

    init {
        renderer.onSettled = { id -> repaintReactive(id.uuid) }
    }

    /** Redraw the reactive slots of [uuid]'s open GUI from the renderer's cache (a resolver finished late). */
    private fun repaintReactive(uuid: UUID) {
        val session = sessions[uuid] ?: return
        val viewer = PlayerId(uuid)
        for ((idx, guiSlot) in session.spec.slots) {
            if (guiSlot is GuiSlot.Reactive) session.inventory.setItem(idx.value, renderer.renderCached(guiSlot, viewer))
        }
    }

    internal fun afterClick(player: IMcPlayer, action: () -> Unit) {
        scheduler.run(com.astarworks.astera.core.application.port.outbound.ExecutionScope.Entity(player.id.uuid), com.astarworks.astera.core.application.port.outbound.TICK, action)
    }

    internal fun onOwner(player: IMcPlayer, action: () -> Unit) {
        scheduler.run(com.astarworks.astera.core.application.port.outbound.ExecutionScope.Entity(player.id.uuid), kotlin.time.Duration.ZERO, action)
    }

    /** Per-player open-GUI state. Main-thread-only; ConcurrentHashMap is a defence-in-depth choice. */
    private val sessions: ConcurrentHashMap<UUID, OpenSession> = ConcurrentHashMap()

    /**
     * Set of player UUIDs the adapter is currently mid-reopening. Mutated and
     * read exclusively from the main thread — [PaperGuiListener.onClose]
     * consults this to suppress its forget-session handler for adapter-driven
     * close events. Visibility is `internal` so the listener (same package)
     * can read directly without a getter.
     */
    internal val reopening: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    override fun open(player: IMcPlayer, spec: GuiSpec): Boolean {
        val target = Bukkit.getPlayer(player.id.uuid) ?: return false
        if (!Bukkit.isOwnedByCurrentRegion(target)) {
            onOwner(player) { open(player, spec) }
            return true
        }
        val uuid = player.id.uuid
        val bukkit = Bukkit.getPlayer(uuid) ?: run {
            log.warn("open() skipped: Bukkit player not online uuid={}", uuid)
            return false
        }

        // If a previous session exists, close it first (adapter-driven, so
        // mark [reopening] to suppress the listener's forget step for the
        // CLOSE event Paper will emit synchronously below). We hold the
        // flag across [openInventory] as well so any out-of-order CLOSE/
        // OPEN ordering still ignores the listener's forget step.
        if (sessions.containsKey(uuid)) {
            reopening += uuid
            bukkit.closeInventory()
        }

        val inv = createAndPopulate(spec, uuid)
        bukkit.openInventory(inv)
        sessions[uuid] = OpenSession(spec, inv)

        // Reopen complete: drop the suppression flag so future user-initiated
        // closes go through PaperGuiListener.onClose normally.
        reopening -= uuid
        return true
    }

    override fun update(player: IMcPlayer, next: GuiSpec) {
        val target = Bukkit.getPlayer(player.id.uuid) ?: return
        if (!Bukkit.isOwnedByCurrentRegion(target)) {
            onOwner(player) { update(player, next) }
            return
        }
        val uuid = player.id.uuid
        val session = sessions[uuid] ?: run {
            log.warn("update() called but no open session for uuid={} (spec={})", uuid, next.id)
            return
        }

        val patches = GuiDiffEngine.diff(session.spec, next)
        if (patches.isEmpty()) return

        // Title changes force close + full-redraw. We detect this first so we
        // never bother applying per-slot patches against an Inventory we're
        // about to throw away.
        val titleChange = patches.any { it is SlotPatch.TitleChange }
        if (titleChange) {
            val bukkit = Bukkit.getPlayer(uuid) ?: run {
                log.warn("update() title-reopen skipped: player offline uuid={}", uuid)
                sessions.remove(uuid)
                return
            }
            reopening += uuid
            try {
                bukkit.closeInventory()
                val inv = createAndPopulate(next, uuid)
                bukkit.openInventory(inv)
                sessions[uuid] = OpenSession(next, inv)
            } finally {
                reopening -= uuid
            }
            return
        }

        // Fast path: apply per-slot patches in place.
        for (patch in patches) {
            when (patch) {
                is SlotPatch.Set -> {
                    val stack = renderer.render(
                        patch.slot ?: GuiSlot.Static(patch.itemId, count = patch.count),
                        player.id,
                    )
                    session.inventory.setItem(patch.index.value, stack)
                }
                is SlotPatch.Clear -> session.inventory.clear(patch.index.value)
                is SlotPatch.TitleChange -> {
                    // Already handled above; defensive no-op.
                }
            }
        }
        sessions[uuid] = OpenSession(next, session.inventory)
    }

    override fun close(player: IMcPlayer) {
        val target = Bukkit.getPlayer(player.id.uuid) ?: return
        if (!Bukkit.isOwnedByCurrentRegion(target)) {
            onOwner(player) { close(player) }
            return
        }
        val uuid = player.id.uuid
        if (sessions[uuid] == null) return
        // Drop session bookkeeping first; the subsequent CLOSE event handler
        // in PaperGuiListener will call forgetSession() too, which is
        // idempotent on a map that no longer has the entry.
        sessions.remove(uuid)
        Bukkit.getPlayer(uuid)?.closeInventory()
    }

    override fun currentSpec(player: IMcPlayer): GuiSpec? =
        sessions[player.id.uuid]?.spec

    /**
     * Listener-facing trapdoor: drop the session record for [uuid] without
     * touching Bukkit. Called from [PaperGuiListener.onClose] when a player
     * closes the GUI themselves, and from [PaperGuiListener.onQuit] when a
     * player disconnects mid-GUI.
     */
    internal fun forgetSession(uuid: UUID) {
        sessions.remove(uuid)
    }

    /**
     * Create the Bukkit `Inventory` for [spec], populate every slot via the
     * renderer, and return the live inventory ready to be opened.
     *
     * Common path between [open] and the title-change branch of [update].
     */
    private fun createAndPopulate(
        spec: GuiSpec,
        uuid: UUID,
    ): Inventory {
        val titleStr = messages.render(uuidToPlayerIdOrNull(uuid), spec.title)
        val titleComponent = AsteraTextDeserializer.deserialize(titleStr)
        val holder = PaperInventoryHolder(spec.id)
        val inv = Bukkit.createInventory(holder, spec.size.slotCount, titleComponent)
        holder.boundInventory = inv

        // Render every populated slot. Empty slots stay air (the inventory
        // is freshly created, so we don't need to clear anything).
        for ((idx, guiSlot) in spec.slots) {
            val stack = renderer.render(guiSlot, uuidToPlayerIdOrNull(uuid))
            if (stack != null) {
                inv.setItem(idx.value, stack)
            }
        }
        return inv
    }

    /** Wrap [uuid] into a [PlayerId] for the message renderer. */
    private fun uuidToPlayerIdOrNull(uuid: UUID): PlayerId = PlayerId(uuid)

    /**
     * Single per-player open-GUI record. Holds the immutable [GuiSpec] the
     * player last saw and the live [Inventory] backing it; diff applies
     * patches against the inventory and replaces the [spec] field by
     * creating a new [OpenSession] (the record is a `data class` so we get
     * structural equality cheap).
     */
    internal data class OpenSession(
        val spec: GuiSpec,
        val inventory: Inventory,
    )

    private companion object {
        val MM: MiniMessage = MiniMessage.miniMessage()
        val log = LoggerFactory.getLogger(PaperGuiAdapter::class.java)
    }
}
