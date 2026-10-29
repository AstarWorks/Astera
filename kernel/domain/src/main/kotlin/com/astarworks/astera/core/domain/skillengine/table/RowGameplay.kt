package com.astarworks.astera.core.domain.skillengine.table

/** The `effect` ops that are presentation (they may be detached); everything else an [Action.Effect] carries is gameplay. */
private val PRESENTATION_EFFECTS = setOf("held_model", "body", "screen_flash", "screen_veil", "camera_shake", "stop_sound")

/** What a gameplay row is called in the `detach:` error, or null for a presentation row. */
public fun Row.gameplayName(): String? = when (val a = action) {
    is Action.Hit -> "hit"
    is Action.Damage -> "damage"
    is Action.Status -> "status"
    is Action.World -> a.op.value
    is Action.Lock -> "lock"
    is Action.Emit -> "emit"
    is Action.Cancel -> "cancel"
    is Action.Call -> "call"
    is Action.Spawn -> if (a.kind == SpawnKind.PROJECTILE_BODY) "projectile" else null
    is Action.Effect -> {
        val kind = (a.payload["kind"] as? Val.Known<*>)?.value as? String
        when {
            kind == "rig" -> null
            kind != null -> kind
            a.op.value in PRESENTATION_EFFECTS -> null
            else -> a.op.value
        }
    }
    else -> null
}

/** A row that changes the game (a pick is one: it decides who the later rows hit). */
public val Row.isGameplayRow: Boolean get() = gameplayName() != null || action is Action.Pick

/**
 * ADR-0303 rev4.14 rule D: a non-detached gameplay row of a handler sub-table belongs to the cast, so it plays at its load-fixed tick after
 * the owner state (or the machine) has ended. A `lock` is the one gameplay row the state still owns (its window ends with the state).
 */
public val Row.drainsAfterExit: Boolean get() = !detached && isGameplayRow && action !is Action.Lock
