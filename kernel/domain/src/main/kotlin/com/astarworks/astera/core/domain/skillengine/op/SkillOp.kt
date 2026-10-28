package com.astarworks.astera.core.domain.skillengine.op

import com.astarworks.astera.core.domain.skillengine.model.Value

/**
 * One handler / timeline entry. Every op has a unique `id` within its skill (so patches can target
 * it) and an optional `when: "= <expr>"` guard.
 *
 * Extension rule (ADR-0301): each op FAMILY is one sealed sub-interface in its own file in this
 * package (FrameOps.kt, DamageOps.kt, ...). A worker adding ops edits only its family file, its
 * `OpDecoder` and its `OpExecutor` (application layer); no central `when` exists.
 */
public sealed interface SkillOp {
    public val id: String
    public val whenExpr: Value<Boolean>?
}

/** Ops allowed in the `gameplay` section: they move frames, decide hits and apply effects. */
public sealed interface GameplayOp : SkillOp

/**
 * Ops allowed in the `presentation` section: they attach to frames and points and cannot move
 * frames, hit, damage or raise events (so a skin can never change contacts).
 */
public sealed interface PresentationOp : SkillOp

public enum class OpSection { GAMEPLAY, PRESENTATION, CONTROL }
