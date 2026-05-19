package com.astarworks.astera.core.application.usecase.vault

/**
 * Sealed error hierarchy for vault use cases (Wave 5e).
 *
 * Keep failures as values (Konsist forbids raw JVM throwables in
 * `*.application.*`). Add a variant per new failure mode — never collapse into
 * a single `String`-bearing fallback.
 */
public sealed interface VaultError {
    /**
     * A [com.astarworks.astera.core.domain.vault.PersonalVault.VaultItem]
     * arrived with `count < 1`. The domain `init` block also rejects this on
     * construction, but [SaveVaultUseCase] validates explicitly so the failure
     * is observable via the use case API instead of as an
     * `IllegalArgumentException`.
     */
    public data class InvalidCount(val itemId: String, val count: Int) : VaultError
}
