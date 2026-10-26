package com.astarworks.astera.core.application.usecase.vault

/**
 * Sealed error hierarchy for vault use cases.
 *
 * Keep failures as values (Konsist forbids raw JVM throwables in
 * `*.application.*`). Add a variant per new failure mode — never collapse into
 * a single `String`-bearing fallback.
 */
public sealed interface VaultError {
    /** A vault item arrived with `count < 1`. */
    public data class InvalidCount(val itemId: String, val count: Int) : VaultError

    /** A vault item addressed a slot outside `0 until PersonalVault.SLOT_COUNT`. */
    public data class InvalidSlot(val slot: Int) : VaultError

    /** The player is in a match; the vault is lobby-only so a match can never leak items out. */
    public data object BlockedInMatch : VaultError

    /** The host refused to show the vault window (player offline etc.). */
    public data object WindowUnavailable : VaultError
}
