package com.astarworks.astera.core.domain.world

/**
 * Sealed failure mode for `IBlockChangeJournalRepository` (Wave 6c,
 * ADR-0160).
 *
 * Distinct from [com.astarworks.astera.core.domain.block.BlockGatewayError]
 * — the journal is a write-through persistence port, not a gameplay
 * gateway. Its failures are strictly storage-shaped (Postgres connection
 * loss, serialization mismatch); they do NOT signal gameplay outcomes
 * (broken / ineffective / immune). The gateway translates a journal
 * failure into either a best-effort log-WARN (for save) or a typed
 * `BlockGatewayError.Storage` (for the warm-up load path where missing
 * state is a hard problem).
 *
 * Per `coding-posture.md` "no raw JVM throwables in application API":
 * implementations catch SQLException / serialization exceptions and
 * surface them as [Storage] with the exception message in `reason`.
 */
public sealed interface JournalError {

    /** Storage-layer failure (Postgres I/O, JSONB serialization, in-memory map corruption). */
    public data class Storage(val reason: String) : JournalError
}
