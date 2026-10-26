package com.astarworks.astera.core.domain.sign

import com.astarworks.astera.core.application.command.CommandSpec
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.gui.GuiSpec
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Declarative effect a sign produces when a player right-clicks it
 * (Wave 5block-embed, ADR-0120).
 *
 * Sealed because the dispatch path does an exhaustive `when`. Variants
 * line up with the four primary use cases the Block audit found uncovered:
 *
 *  - [RunCommand] — execute a declarative command. Use case: spawn-warp
 *    signs (`/spawn`, `/lobby`) authored without giving operators raw
 *    command-block access.
 *  - [OpenGui] — render a [GuiSpec]. Use case: in-game shop signs that
 *    open the catalogue GUI on right-click.
 *  - [TeleportTo] — direct teleport. Use case: hub-room portals built
 *    out of two coordinate signs instead of full portal frames.
 *  - [Display] — render an i18n [MessageKey] to the interacting player.
 *    Use case: rule signs, lore signs, NPC-style flavour text that needs
 *    to support en/ja parity.
 *
 * The sign-action vocabulary deliberately mirrors
 * [com.astarworks.astera.core.application.port.outbound.block.BlockInteractionAction]
 * (block-position routing). The two ports stay separate because:
 *
 *  - Signs have a per-line authoring convention (`[ASTERA]` magic header)
 *    that drives the parser path; positional bindings are wired by use
 *    cases or stage YAML.
 *  - The sign domain is value-driven (text-parse → binding); the block
 *    binding domain is handle-driven (use-case calls `bind*`).
 *
 * Keeping the sealed surfaces distinct lets each evolve independently
 * without binary-breaking the other (Phase 1 forward-only posture).
 */
public sealed interface SignAction {

    public data class RunCommand(public val command: CommandSpec) : SignAction

    public data class OpenGui(public val gui: GuiSpec) : SignAction

    public data class TeleportTo(
        public val world: WorldId,
        public val position: Vec3,
    ) : SignAction

    public data class Display(public val textKey: MessageKey) : SignAction
}
