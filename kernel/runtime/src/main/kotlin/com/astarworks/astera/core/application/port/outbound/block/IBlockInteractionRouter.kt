package com.astarworks.astera.core.application.port.outbound.block

import com.astarworks.astera.core.application.command.CommandSpec
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.gui.GuiSpec
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.world.BlockInteractAction
import com.astarworks.astera.core.domain.world.WorldId
import java.util.UUID

/**
 * Outbound port: bind one declarative [BlockInteractionAction] per
 * `(world, position, action)` triple and look it up at dispatch time
 * (Wave 5block-embed, ADR-0120).
 *
 * # Why this exists
 *
 * Wave 5world-events (ADR-0111) introduced the
 * [com.astarworks.astera.core.domain.event.PlayerInteractedBlock] event,
 * but a Block audit found:
 *
 *  - The event has **zero subscribers** in production — every Bukkit-side
 *    block-click instantly hits the floor.
 *  - There is no `IBlockInteractionRouter` analogue of the GUI's
 *    [com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter]
 *    or the NPC's
 *    [com.astarworks.astera.core.application.port.outbound.entity.INpcInteractionRouter].
 *  - Authors who want "right-click this block opens the shop" have no
 *    target to bind against.
 *
 * This port is the **routing table** for block-position interactions.
 * The companion `RouteBlockInteractionListener` (event-bus listener)
 * resolves the bound action and dispatches it through
 * `IAsteraCommandRegistry` / `IGuiAdapter` / skill use cases / `IWorldGateway`.
 *
 * # Binding scope
 *
 * Bindings are **per-position**: every bound block resolves to one action
 * for `RightClick` and one for `LeftClick`. Physical contact and off-hand
 * use stay routed by other systems (region permission for `PhysicalContact`,
 * future Wave-6+ off-hand handling) so this port stays minimal.
 *
 * # Threading
 *
 * All methods are called on the main thread. Per-position state is held
 * in a plain map; the in-memory impl is not thread-safe.
 *
 * # Cross-plugin
 *
 * Lives in `plugin/core` so feature plugins (Stage admin tools,
 * Match interaction, etc.) can claim block bindings via
 * `services.getOrThrow<IBlockInteractionRouter>()`.
 */
public interface IBlockInteractionRouter {

    /**
     * Bind [action] to right-click at ([world], [position]). Replaces any
     * existing right-click binding for the same position; the returned
     * [BlockBindingHandle] is the new owner of the binding.
     */
    public fun bindRightClick(
        world: WorldId,
        position: Vec3,
        action: BlockInteractionAction,
    ): BlockBindingHandle

    /**
     * Bind [action] to left-click at ([world], [position]). Same semantics
     * as [bindRightClick] but for the left-click channel.
     */
    public fun bindLeftClick(
        world: WorldId,
        position: Vec3,
        action: BlockInteractionAction,
    ): BlockBindingHandle

    /**
     * Cancel the binding identified by [handle]. Returns `true` if anything
     * was removed; `false` if the binding was already gone (double-unbind,
     * stage reload race).
     */
    public fun unbind(handle: BlockBindingHandle): Boolean

    /**
     * Resolve the bound action for [action] at ([world], [position]), or
     * `null` if no binding exists. The router responds to
     * [BlockInteractAction.RightClick] and [BlockInteractAction.LeftClick];
     * any other interact kind always returns `null` (kept structured so
     * future variants can be added without binary-breaking the port).
     */
    public fun lookup(
        world: WorldId,
        position: Vec3,
        action: BlockInteractAction,
    ): BlockInteractionAction?
}

/**
 * Declarative target action for a block interaction binding
 * (Wave 5block-embed, ADR-0120).
 *
 * Sealed because the dispatch listener does an exhaustive `when` to wire
 * each variant to its target port. Adding a new variant is a deliberate
 * forward-compatibility step per CLAUDE.md §Pre-release posture.
 *
 * # Variants
 *
 * - [RunCommand] — execute a declarative [CommandSpec] as the interacting
 *   player. The listener wraps this in a console-or-player command path
 *   depending on the command's permission requirements (Wave 6b adapter
 *   detail).
 * - [OpenGui] — render [GuiSpec] for the interacting player through
 *   `IGuiAdapter.open(...)`.
 * - [TriggerSkill] — invoke the [SkillId] cast use case for the player as
 *   if they pressed a skill trigger.
 * - [TeleportTo] — relocate the player via `IWorldGateway.teleport(...)`.
 * - [Custom] — escape hatch for plugin-defined intents not covered by the
 *   four core variants. The string [intentKey] is dispatched through the
 *   GUI intent router (longest-prefix wins) so plugins can claim a
 *   namespace (`stage.npc-tag.`, `event.beacon.`, …) the same way they do
 *   for GUI clicks. Payload is the empty map at the dispatcher; callers
 *   that need parameterised intents should add a new sealed variant
 *   rather than parsing strings.
 */
public sealed interface BlockInteractionAction {

    public data class RunCommand(public val command: CommandSpec) : BlockInteractionAction

    public data class OpenGui(public val gui: GuiSpec) : BlockInteractionAction

    public data class TriggerSkill(public val skillId: SkillId) : BlockInteractionAction

    public data class TeleportTo(
        public val world: WorldId,
        public val position: Vec3,
    ) : BlockInteractionAction

    public data class Custom(public val intentKey: String) : BlockInteractionAction {
        init {
            require(intentKey.isNotBlank()) { "Custom.intentKey must not be blank" }
            require(VALID_KEY.matches(intentKey)) {
                "Custom.intentKey must be dot-separated lower kebab/snake-case " +
                    "(a-z, 0-9, -, _, .): '$intentKey'"
            }
        }

        public companion object {
            private val VALID_KEY = Regex("^[a-z][a-z0-9_-]*(\\.[a-z0-9_-]+)*$")
        }
    }
}

/**
 * Opaque handle returned by `IBlockInteractionRouter.bindRightClick / bindLeftClick`,
 * usable with `IBlockInteractionRouter.unbind` to cancel the binding.
 *
 * Backed by a UUID so handles are unique across re-binds (a re-bind to the
 * same position produces a different handle, which lets stage reload code
 * track and unbind only its own previously-installed bindings without
 * trampling another plugin's binding at the same coordinates).
 */
@JvmInline
public value class BlockBindingHandle(public val value: UUID) {
    public companion object {
        public fun random(): BlockBindingHandle = BlockBindingHandle(UUID.randomUUID())
    }
}
