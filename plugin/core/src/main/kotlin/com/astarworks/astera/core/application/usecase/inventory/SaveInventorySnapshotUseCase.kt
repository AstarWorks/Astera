package com.astarworks.astera.core.application.usecase.inventory

import com.astarworks.astera.core.application.port.outbound.IPlayerInventorySnapshotRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerInventorySnapshot
import java.time.Clock

/**
 * Persist a [PlayerInventorySnapshot] for later restore (Wave 5e / RTM issue #5).
 *
 * Stamps [PlayerInventorySnapshot.savedAt] with `clock.instant()` (always —
 * even if the caller pre-populated one, the use-case authoritative time wins
 * so audit + integrity testing have a single clock-injectable source). Then
 * upserts via [IPlayerInventorySnapshotRepository] (latest-write-wins).
 *
 * Validation is enforced by the domain `init` block (sizes 9 / 27 / 4); if a
 * caller passes a malformed snapshot the [IllegalArgumentException] is caught
 * at the use-case boundary and surfaced as [InventorySnapshotError.InvalidShape]
 * — the application-API rule (no raw JVM throwables; sealed errors + Result)
 * holds per `ArchitectureTest.application layers must not import raw JVM
 * throwable types`.
 */
public class SaveInventorySnapshotUseCase(
    private val repo: IPlayerInventorySnapshotRepository,
    private val clock: Clock = Clock.systemUTC(),
) {

    public fun save(snapshot: PlayerInventorySnapshot): Result<Unit, InventorySnapshotError> {
        val stamped = try {
            snapshot.copy(savedAt = clock.instant())
        } catch (e: IllegalArgumentException) {
            // `copy` cannot fail today (sizes are already validated on the source
            // instance), but the defensive catch lets future shape additions in
            // `init` surface as a typed failure instead of a leak.
            return Result.failure(InventorySnapshotError.InvalidShape(e.message ?: "invalid snapshot shape"))
        }
        repo.save(stamped)
        return Result.success(Unit)
    }
}

/**
 * Expected failure modes shared by [SaveInventorySnapshotUseCase] and
 * [RestoreInventorySnapshotUseCase] (Wave 5e).
 *
 * - [InvalidShape]: domain `init` validation failed — sizes are wrong
 *   or a [com.astarworks.astera.core.domain.player.InventoryItemSlot.ContentItem]
 *   carries an out-of-range count. Caller bug; the message describes the
 *   constraint that fired.
 * - [RestoreFailed]: the repository round-trip failed (e.g., Postgres
 *   unavailable). Reserved for Wave 6b — Wave 5e's repo paths cannot raise
 *   today, but the variant exists so adding the failure later doesn't
 *   constitute an API break (silly-knuth §5: pre-declare the discriminant).
 */
public sealed class InventorySnapshotError {
    public data class InvalidShape(val reason: String) : InventorySnapshotError()
    public data class RestoreFailed(val reason: String) : InventorySnapshotError()
}
