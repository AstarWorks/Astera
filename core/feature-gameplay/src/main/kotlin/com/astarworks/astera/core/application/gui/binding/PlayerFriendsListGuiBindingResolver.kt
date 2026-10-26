package com.astarworks.astera.core.application.gui.binding

import com.astarworks.astera.core.application.port.outbound.IFriendshipRepository
import com.astarworks.astera.core.application.port.outbound.IGuiBindingResolver
import com.astarworks.astera.core.application.port.outbound.IPlayerSessionRepository
import com.astarworks.astera.core.domain.gui.BindingKey
import com.astarworks.astera.core.domain.gui.ResolvedReactiveSlot
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.SessionState
import com.astarworks.astera.core.domain.social.Friendship
import com.astarworks.astera.core.domain.social.FriendshipStatus
import org.slf4j.LoggerFactory

/**
 * Wave M 5polish-quickwin (ADR-0130 §A) — resolves `player.friends.list.<n>`
 * bindings into per-friend tiles.
 *
 * # Binding shape
 *
 * `player.friends.list.0` … `player.friends.list.8` — 9 slots, indexed from
 * 0. Wave 5lobby-content's [com.astarworks.astera.core.application.gui.lobby.FriendsListGuiSpec]
 * skeleton already declares the surface; this resolver fills in the tiles by
 * joining the viewer's [Friendship] list (Wave 5e) with their current
 * [com.astarworks.astera.core.domain.player.PlayerSession] (Wave 5e) to
 * surface online / offline status.
 *
 * # Why only 9 slots
 *
 * Phase 1 PoC keeps the spec readable and the resolver O(N) on the friend list.
 * Pagination (slots → page index payload) lands in Phase 2 once the surface
 * matures.
 *
 * # Status colour mapping
 *
 * - `ACCEPTED` + session present + state != Disconnected → `indicator-friend-online`
 * - `ACCEPTED` + (no session OR Disconnected) → `indicator-friend-offline`
 * - `PENDING` → `indicator-friend-pending`
 * - `BLOCKED` / `DECLINED` → omitted from the list (the viewer should
 *   navigate to a separate "blocked" pane to manage these — out of scope
 *   for this wave).
 *
 * The display name shown in the tile lore is derived via a [MessageKey]
 * placeholder so locale + glyph composition stay consistent with the rest of
 * the GUI surface.
 *
 * # Cross-plugin classloader rule
 *
 * Lives in `plugin/core` — depends only on Core ports. Combat / Match are
 * not touched. The repository providers (lambdas) let the wiring be lazy so
 * the resolver can be constructed before persistence overlays finish loading.
 */
public class PlayerFriendsListGuiBindingResolver(
    private val friendshipsProvider: () -> IFriendshipRepository?,
    private val sessionsProvider: () -> IPlayerSessionRepository?,
) : IGuiBindingResolver {

    /**
     * Eager constructor for tests / call sites where every dependency is
     * already resolved. Pass `null` for collaborators the test does not
     * exercise.
     */
    public constructor(
        friendships: IFriendshipRepository?,
        sessions: IPlayerSessionRepository?,
    ) : this(
        friendshipsProvider = { friendships },
        sessionsProvider = { sessions },
    )

    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun resolve(binding: BindingKey, viewer: PlayerId?): ResolvedReactiveSlot? {
        if (viewer == null) return null
        val key = binding.value
        if (!key.startsWith(PREFIX)) return null
        val indexToken = key.removePrefix(PREFIX)
        val index = indexToken.toIntOrNull()
        if (index == null || index !in 0 until MAX_SLOTS) {
            log.warn(
                "PlayerFriendsListGuiBindingResolver: index out of range '{}' (expected 0..{})",
                indexToken,
                MAX_SLOTS - 1,
            )
            return null
        }
        val friendships = friendshipsProvider()
        if (friendships == null) {
            log.debug("Friendship provider not wired yet; binding '{}' returns null.", key)
            return null
        }
        val visible = friendships.forPlayer(viewer)
            .filter { it.status == FriendshipStatus.ACCEPTED || it.status == FriendshipStatus.PENDING }
            .sortedWith(compareBy({ it.status.ordinal }, { it.createdAt }))
        val friend = visible.getOrNull(index) ?: return null
        val otherId = otherSide(friend, viewer)
        val (itemId, displayKey) = tileFor(friend.status, otherId)
        return ResolvedReactiveSlot(
            itemId = itemId,
            displayOverride = displayKey,
        )
    }

    private fun otherSide(friend: Friendship, viewer: PlayerId): PlayerId =
        if (friend.playerA == viewer) friend.playerB else friend.playerA

    private fun tileFor(status: FriendshipStatus, otherId: PlayerId): Pair<ItemId, MessageKey> {
        if (status == FriendshipStatus.PENDING) {
            return ItemId(TILE_PENDING) to MSG_NAME_PENDING
        }
        val sessions = sessionsProvider()
        val online = sessions?.find(otherId)?.let { it.state != SessionState.Disconnected } == true
        val item = if (online) TILE_ONLINE else TILE_OFFLINE
        val key = if (online) MSG_NAME_ONLINE else MSG_NAME_OFFLINE
        return ItemId(item) to key
    }

    public companion object {
        public const val PREFIX: String = "player.friends.list."
        public const val MAX_SLOTS: Int = 9

        public const val TILE_ONLINE: String = "indicator-friend-online"
        public const val TILE_OFFLINE: String = "indicator-friend-offline"
        public const val TILE_PENDING: String = "indicator-friend-pending"

        public val MSG_NAME_ONLINE: MessageKey = MessageKey("astera.gui.binding.friend.online")
        public val MSG_NAME_OFFLINE: MessageKey = MessageKey("astera.gui.binding.friend.offline")
        public val MSG_NAME_PENDING: MessageKey = MessageKey("astera.gui.binding.friend.pending")
    }
}
