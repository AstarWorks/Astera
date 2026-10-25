package com.astarworks.astera.core.application.service.presentation

import com.astarworks.astera.core.application.port.outbound.presentation.IPlayerPresentation
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.presentation.PresentationAudience
import com.astarworks.astera.core.domain.presentation.PresentationDisplay
import com.astarworks.astera.core.domain.presentation.PresentationError
import com.astarworks.astera.core.domain.presentation.PresentationKind
import com.astarworks.astera.core.domain.presentation.PresentationLease
import com.astarworks.astera.core.domain.presentation.PresentationMask
import java.util.UUID

/**
 * Headless [IPlayerPresentation]: the same lease rules as the Paper impl, plus a model of what each viewer's client has
 * received ([view]) so scenarios can count the weapons a viewer sees per tick. Bundles apply instantly (a bundle is one
 * client step). [viewers] lists everyone who could track an owner.
 */
public class InMemoryPlayerPresentation(
    private val viewers: () -> Collection<PlayerId> = { emptyList() },
) : IPlayerPresentation {

    /** What one viewer's client currently shows of an owner. */
    public data class View(
        public val handHidden: Boolean,
        public val armorHidden: Boolean,
        public val bodyHidden: Boolean,
        /** Staged displays this viewer's client was told to draw. */
        public val revealed: Set<UUID>,
    )

    private class Lease(val handle: PresentationLease, val onRevoked: () -> Unit) {
        var active = false
        var ownerLeg = true
        var othersLeg = true
    }

    private val leases = HashMap<PlayerId, MutableList<Lease>>()
    private val views = HashMap<Pair<PlayerId, PlayerId>, View>()
    private var next = 0L

    /** Every transition, for assertions. */
    public val log: MutableList<String> = mutableListOf()

    /** Owners whose pose is currently pinned. */
    public val pinnedPoses: MutableSet<PlayerId> = HashSet()

    override fun claim(owner: PlayerId, mask: PresentationMask, kind: PresentationKind, onRevoked: () -> Unit): Result<PresentationLease, PresentationError> {
        val held = leases[owner].orEmpty()
        if (kind == PresentationKind.EMOTE && held.any { it.handle.kind == PresentationKind.PERFORMANCE }) {
            return Result.failure(PresentationError.Busy(PresentationKind.PERFORMANCE))
        }
        if (kind == PresentationKind.PERFORMANCE) held.filter { it.handle.kind == PresentationKind.EMOTE }.forEach { revoke(it) }
        val lease = Lease(PresentationLease(++next, owner, mask, kind), onRevoked)
        leases.getOrPut(owner) { ArrayList() } += lease
        return Result.success(lease.handle)
    }

    override fun reveal(lease: PresentationLease, displays: List<PresentationDisplay>) {
        val l = find(lease) ?: return
        l.active = true
        displays.forEach { it.beforeSend() }
        refresh(lease.owner, add = displays.map { it.id }.toSet(), remove = emptySet(), ownerLeg = true, othersLeg = true)
        log += "reveal ${lease.owner.uuid} ${displays.size}"
    }

    override fun flushBundled(owner: PlayerId, displays: List<PresentationDisplay>) {
        displays.forEach { it.beforeSend() }
        log += "flush ${owner.uuid} ${displays.size}"
    }

    override fun release(lease: PresentationLease, displays: List<UUID>, audience: PresentationAudience) {
        val l = find(lease) ?: return
        val owner = audience != PresentationAudience.OTHERS
        val others = audience != PresentationAudience.OWNER
        if (owner) l.ownerLeg = false
        if (others) l.othersLeg = false
        refresh(lease.owner, add = emptySet(), remove = displays.toSet(), ownerLeg = owner, othersLeg = others)
        if (!l.ownerLeg && !l.othersLeg) drop(l)
        log += "release ${lease.owner.uuid} $audience"
    }

    override fun holder(owner: PlayerId): PresentationKind? =
        leases[owner].orEmpty().map { it.handle.kind }.minByOrNull { it.ordinal }

    override fun shutdown() {
        leases.values.flatten().toList().forEach { revoke(it) }
    }

    /** The owner left or changed world: everything is given back at once. */
    public fun forceRelease(owner: PlayerId) {
        leases[owner].orEmpty().toList().forEach { revoke(it) }
    }

    /** [viewer] starts tracking [owner]: pairing data carries the real equipment, the track hook re-masks in the same tick. */
    public fun viewerTracks(viewer: PlayerId, owner: PlayerId) {
        refresh(owner, add = emptySet(), remove = emptySet(), ownerLeg = false, othersLeg = true, only = viewer)
    }

    /** What [viewer] currently sees of [owner]. */
    public fun view(viewer: PlayerId, owner: PlayerId): View =
        views[owner to viewer] ?: View(handHidden = false, armorHidden = false, bodyHidden = false, revealed = emptySet())

    private val revealedOf = HashMap<PlayerId, Set<UUID>>()

    private fun unionFor(owner: PlayerId, ownerView: Boolean): PresentationMask =
        leases[owner].orEmpty().filter { if (ownerView) it.ownerLeg else it.othersLeg }
            .fold(PresentationMask.NONE) { acc, l -> acc + l.handle.mask }

    private fun refresh(owner: PlayerId, add: Set<UUID>, remove: Set<UUID>, ownerLeg: Boolean, othersLeg: Boolean, only: PlayerId? = null) {
        revealedOf[owner] = (revealedOf[owner].orEmpty() + add) - remove
        for (viewer in (viewers() + owner).toSet()) {
            if (only != null && viewer != only) continue
            val isOwner = viewer == owner
            if (isOwner && !ownerLeg) continue
            if (!isOwner && !othersLeg) continue
            val mask = unionFor(owner, isOwner)
            val key = owner to viewer
            val old = views[key]
            val revealed = if (only != null) revealedOf[owner].orEmpty() else old?.revealed.orEmpty() + add - remove
            val next = View(mask.hand, mask.armor && !isOwner, mask.body, revealed)
            if (!next.handHidden && !next.armorHidden && !next.bodyHidden && next.revealed.isEmpty()) views.remove(key) else views[key] = next
        }
        if (unionFor(owner, true).pinPose || unionFor(owner, false).pinPose) pinnedPoses += owner else pinnedPoses -= owner
    }

    private fun find(lease: PresentationLease): Lease? = leases[lease.owner]?.firstOrNull { it.handle.id == lease.id }

    private fun drop(l: Lease) {
        val owner = l.handle.owner
        leases[owner]?.remove(l)
        if (leases[owner].isNullOrEmpty()) {
            leases.remove(owner)
            revealedOf.remove(owner)
            pinnedPoses -= owner
        }
    }

    private fun revoke(l: Lease) {
        val owner = l.handle.owner
        l.ownerLeg = false
        l.othersLeg = false
        drop(l)
        refresh(owner, add = emptySet(), remove = revealedOf[owner].orEmpty() + views.filterKeys { it.first == owner }.values.flatMap { it.revealed },
            ownerLeg = true, othersLeg = true)
        l.onRevoked()
    }
}
