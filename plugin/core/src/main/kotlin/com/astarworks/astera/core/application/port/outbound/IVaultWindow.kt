package com.astarworks.astera.core.application.port.outbound

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
    public fun show(vault: PersonalVault): Boolean
}
