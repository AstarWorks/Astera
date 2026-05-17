package com.astarworks.astera.weapons.application.usecase.loadout

import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.application.port.outbound.IGuiRegistry
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.gui.GuiId
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Resolves a [GuiId] to its registered [com.astarworks.astera.core.domain.gui.GuiSpec]
 * and asks the platform [IGuiAdapter] to open it for the given player.
 *
 * Wave 4 R3 use case (ADR-0021). The use case is intentionally tiny: most of the
 * heavy lifting (diff rendering, click → intent translation) lives in the
 * adapter and the [com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter].
 * The job here is to:
 *
 * 1. Confirm the player is online ([IMcServer.findPlayer] → non-null).
 * 2. Confirm the GUI id is known to [IGuiRegistry].
 * 3. Push the spec into the adapter and propagate its `false` (rejected) result
 *    as a typed [OpenGuiError.OpenRejected].
 *
 * Errors are returned as a sealed [OpenGuiError] so command executors and any
 * future inbound handlers (chat aliases, NPC interactions, scheduled UI prompts)
 * can map them to the right `MessageKey` without re-implementing the failure
 * taxonomy.
 */
public class OpenGuiUseCase(
    private val adapter: IGuiAdapter,
    private val registry: IGuiRegistry,
    private val server: IMcServer,
) {
    public fun execute(playerId: PlayerId, guiId: GuiId): Result<Unit, OpenGuiError> {
        val player = server.findPlayer(playerId)
            ?: return Result.failure(OpenGuiError.PlayerNotOnline)
        val spec = registry.resolve(guiId)
            ?: return Result.failure(OpenGuiError.GuiNotFound(guiId))
        return if (adapter.open(player, spec)) {
            Result.success(Unit)
        } else {
            Result.failure(OpenGuiError.OpenRejected)
        }
    }
}

/** Expected failure modes of [OpenGuiUseCase.execute]. */
public sealed class OpenGuiError {
    /** The target [PlayerId] has no online session on this server. */
    public data object PlayerNotOnline : OpenGuiError()

    /** No [com.astarworks.astera.core.domain.gui.GuiSpec] is registered under [id]. */
    public data class GuiNotFound(public val id: GuiId) : OpenGuiError()

    /**
     * The adapter declined to open the spec (invalid item refs, race with
     * disconnect, etc.). The adapter is responsible for logging the underlying
     * cause; from the use case's perspective only the boolean outcome matters.
     */
    public data object OpenRejected : OpenGuiError()
}
