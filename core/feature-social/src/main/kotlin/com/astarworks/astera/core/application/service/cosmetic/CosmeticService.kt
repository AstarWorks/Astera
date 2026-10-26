package com.astarworks.astera.core.application.service.cosmetic

import com.astarworks.astera.core.application.port.outbound.IPlayerUnlockView
import com.astarworks.astera.core.application.port.outbound.cosmetic.ICosmeticCatalog
import com.astarworks.astera.core.application.port.outbound.cosmetic.ICosmeticLoadoutRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.cosmetic.Cosmetic
import com.astarworks.astera.core.domain.cosmetic.CosmeticKind
import com.astarworks.astera.core.domain.cosmetic.CosmeticLoadout
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.profile.CosmeticSummary
import com.astarworks.astera.core.domain.profile.TitleSummary
import java.util.concurrent.CopyOnWriteArrayList

public sealed interface CosmeticError {
    public data object Unknown : CosmeticError
    public data object NotOwned : CosmeticError
}

/**
 * Ownership + equip rules for cosmetics (ECO-02). Ownership lives in the shared unlock store
 * (`title:` / `cosmetic:` entitlements), the equipped choice in [ICosmeticLoadoutRepository].
 * Chat, tab list and the profile card read through here; [addChangeListener] lets the Paper
 * display refresh when a player equips something.
 */
public class CosmeticService(
    private val catalog: ICosmeticCatalog,
    private val unlocks: () -> IPlayerUnlockView?,
    private val loadouts: ICosmeticLoadoutRepository,
) {
    private val listeners = CopyOnWriteArrayList<(PlayerId) -> Unit>()

    public fun addChangeListener(listener: (PlayerId) -> Unit) {
        listeners += listener
    }

    /** Tell listeners that [playerId]'s ownership or loadout changed (also after a purchase). */
    public fun notifyChanged(playerId: PlayerId) {
        listeners.forEach { runCatching { it(playerId) } }
    }

    public fun catalog(): List<Cosmetic> = catalog.all()

    public fun find(id: String): Cosmetic? = catalog.find(id)

    public suspend fun owns(playerId: PlayerId, cosmetic: Cosmetic): Boolean =
        cosmetic.unlockId in (unlocks()?.unlocks(playerId) ?: emptySet())

    public suspend fun owned(playerId: PlayerId): List<Cosmetic> {
        val held = unlocks()?.unlocks(playerId) ?: return emptyList()
        return catalog.all().filter { it.unlockId in held }
    }

    public fun loadout(playerId: PlayerId): CosmeticLoadout = loadouts.find(playerId)

    /** The equipped cosmetic of [kind], only while it is still owned and still in the catalog. */
    public suspend fun equipped(playerId: PlayerId, kind: CosmeticKind): Cosmetic? =
        loadout(playerId).idOf(kind)?.let(catalog::find)?.takeIf { it.kind == kind && owns(playerId, it) }

    public suspend fun equip(playerId: PlayerId, cosmeticId: String): Result<Cosmetic, CosmeticError> {
        val cosmetic = catalog.find(cosmeticId) ?: return Result.failure(CosmeticError.Unknown)
        if (!owns(playerId, cosmetic)) return Result.failure(CosmeticError.NotOwned)
        loadouts.set(playerId, cosmetic.kind, cosmetic.id)
        notifyChanged(playerId)
        return Result.success(cosmetic)
    }

    public fun unequip(playerId: PlayerId, kind: CosmeticKind) {
        loadouts.set(playerId, kind, null)
        notifyChanged(playerId)
    }

    public suspend fun titleSummary(playerId: PlayerId): TitleSummary = TitleSummary(
        unlockedCount = owned(playerId).count { it.kind == CosmeticKind.TITLE },
        equippedTitleId = equipped(playerId, CosmeticKind.TITLE)?.id,
    )

    public suspend fun cosmeticSummary(playerId: PlayerId): CosmeticSummary {
        val ownedCosmetics = owned(playerId).filter { it.kind != CosmeticKind.TITLE }
        val featured = equipped(playerId, CosmeticKind.KILL_EFFECT) ?: equipped(playerId, CosmeticKind.NAME_COLOR)
        return CosmeticSummary(ownedCosmetics.size, featured?.id)
    }
}
