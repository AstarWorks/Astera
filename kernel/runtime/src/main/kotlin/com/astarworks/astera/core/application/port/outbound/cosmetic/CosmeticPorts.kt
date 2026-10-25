package com.astarworks.astera.core.application.port.outbound.cosmetic

import com.astarworks.astera.core.domain.cosmetic.Cosmetic
import com.astarworks.astera.core.domain.cosmetic.CosmeticKind
import com.astarworks.astera.core.domain.cosmetic.CosmeticLoadout
import com.astarworks.astera.core.domain.player.PlayerId

/** Read access to `content/cosmetics/`. */
public interface ICosmeticCatalog {
    public fun all(): List<Cosmetic>
    public fun find(id: String): Cosmetic?
}

/** Persistent per-player equipped cosmetics (one per [CosmeticKind]). */
public interface ICosmeticLoadoutRepository {
    public fun find(playerId: PlayerId): CosmeticLoadout

    /** Equip [cosmeticId] in the [kind] slot, or clear the slot when it is `null`. */
    public fun set(playerId: PlayerId, kind: CosmeticKind, cosmeticId: String?)
}

/** Plays a kill effect at the place [victim] stands; a no-op where there is no world. */
public interface ICosmeticEffectPlayer {
    public fun playAt(victim: PlayerId, particleKey: String, count: Int)
}
