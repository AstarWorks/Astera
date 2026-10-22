package com.astarworks.astera.core.application.command

import com.astarworks.astera.core.application.usecase.vault.AccessPersonalVaultUseCase
import com.astarworks.astera.core.application.usecase.vault.VaultError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.MessageKey

/**
 * `/vault` — opens the executing player's personal vault (the ender chest).
 *
 * Failure keys: [VAULT_BLOCKED_IN_MATCH] / [VAULT_UNAVAILABLE]; console
 * senders get `astera.command.player-only`.
 */
public fun buildVaultCommandSpec(access: AccessPersonalVaultUseCase): CommandSpec.Literal =
    CommandSpec.Literal(
        name = "vault",
        description = MessageKey("astera.command.vault.root"),
        executor = lambda@{ ctx ->
            val playerId = ctx.sender ?: return@lambda CommandResult.Failure(
                MessageKey("astera.command.player-only"),
            )
            when (val result = access.open(playerId)) {
                is Result.Success -> CommandResult.Success
                is Result.Failure -> CommandResult.Failure(vaultErrorKey(result.error))
            }
        },
    )

public val VAULT_BLOCKED_IN_MATCH: MessageKey = MessageKey("astera.vault.blocked-in-match")
public val VAULT_UNAVAILABLE: MessageKey = MessageKey("astera.vault.unavailable")

/** Maps a [VaultError] to the player-facing message key (shared by `/vault` and the ender chest listener). */
public fun vaultErrorKey(error: VaultError): MessageKey = when (error) {
    VaultError.BlockedInMatch -> VAULT_BLOCKED_IN_MATCH
    is VaultError.InvalidCount, is VaultError.InvalidSlot, VaultError.WindowUnavailable -> VAULT_UNAVAILABLE
}
