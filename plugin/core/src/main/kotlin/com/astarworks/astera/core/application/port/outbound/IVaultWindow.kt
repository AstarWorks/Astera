package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.vault.PersonalVault

/**
 * Outbound port: show a [PersonalVault] to its owner as a free-movement
 * chest-like window and persist the edited contents when it is closed.
 *
 * Unlike [IGuiAdapter] (read-only declarative GUIs, ADR-0021) the vault
 * window must let items move freely, so the host implements it directly
 * (Paper: a 27-slot chest inventory whose close handler calls
 * `SaveVaultUseCase`). Absent in headless hosts; callers get
 * `VaultError.WindowUnavailable`.
 */
public interface IVaultWindow {
    /** @return `true` if the window was shown, `false` if the owner is offline / refused. */
    public fun show(vault: PersonalVault, page: Int = 0): Boolean

    /**
     * Closes [playerId]'s open vault window, if any, which persists its contents. Called before the
     * vault is (re)loaded for a new window: a window opened from a stale copy while the old one is
     * still unsaved would duplicate whatever was taken out of the old one.
     */
    public fun closeFor(playerId: PlayerId)
}
