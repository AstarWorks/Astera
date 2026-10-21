package com.astarworks.astera.combat.application.service

import com.astarworks.astera.core.domain.skill.condition.Condition
import com.astarworks.astera.core.domain.skill.mechanic.Mechanic
import com.astarworks.astera.core.domain.skill.targeter.Targeter

/**
 * Single source of truth for which authored content [MechanicDispatcher] cannot
 * actually execute yet. Every branch listed here only logs (or silently resolves
 * to `0` / `false` / empty) at runtime, so content that uses it would "work"
 * without any visible effect. Content validation
 * (`ValidateContentReferencesUseCase`, `/astera-validate`) uses this to fail
 * loudly instead.
 *
 * Keep in sync with [MechanicDispatcher.dispatch]: when a mechanic gets a real
 * implementation, remove it from [isNoOp] in the same change.
 */
public object MechanicSupport {

    /**
     * Human-readable problems for [mechanic] (recursing into control-flow
     * children). Empty list = fully supported.
     */
    public fun problemsOf(mechanic: Mechanic): List<String> {
        val out = mutableListOf<String>()
        collect(mechanic, out)
        return out
    }

    private fun collect(mechanic: Mechanic, out: MutableList<String>) {
        val name = mechanic::class.simpleName ?: "Mechanic"
        if (isNoOp(mechanic)) out += "mechanic '$name' is not implemented (no-op at runtime)"
        // Cel values anywhere inside the mechanic (amounts, durations, chance,
        // offsets...) evaluate through StubCelEvaluator to 0 / false / ZERO.
        // Every Cel value/condition is a data class named `Cel`, so its
        // rendered form is a reliable marker without walking every field.
        if (CEL_MARKER in mechanic.toString()) {
            out += "mechanic '$name' uses a CEL script (CEL evaluator is a stub: evaluates to 0/false/ZERO)"
        }
        unsupportedTargeter(mechanic.targets)?.let { out += "mechanic '$name' uses $it" }
        mechanic.condition?.let { unsupportedCondition(it, out, name) }
        when (mechanic) {
            is Mechanic.IfThen -> {
                unsupportedCondition(mechanic.predicate, out, name)
                (mechanic.then_ + mechanic.else_).forEach { collect(it, out) }
            }
            is Mechanic.Loop -> mechanic.mechanics.forEach { collect(it, out) }
            is Mechanic.Choose -> mechanic.weighted.flatMap { it.mechanics }.forEach { collect(it, out) }
            else -> Unit
        }
    }

    private fun unsupportedCondition(condition: Condition, out: MutableList<String>, owner: String) {
        when (condition) {
            is Condition.And -> {
                unsupportedCondition(condition.left, out, owner)
                unsupportedCondition(condition.right, out, owner)
            }
            is Condition.Or -> {
                unsupportedCondition(condition.left, out, owner)
                unsupportedCondition(condition.right, out, owner)
            }
            is Condition.Not -> unsupportedCondition(condition.inner, out, owner)
            is Condition.Cel ->
                out += "mechanic '$owner' has a CEL condition (CEL evaluator is a stub: always false)"
            is Condition.HasStatus, is Condition.IsAlly, is Condition.IsEnemy,
            is Condition.HealthBelow, is Condition.DistanceLessThan ->
                out += "mechanic '$owner' has condition '${condition::class.simpleName}' which always evaluates to false"
        }
    }

    private fun unsupportedTargeter(targeter: Targeter): String? = when (targeter) {
        is Targeter.ForwardRay, is Targeter.Cone, is Targeter.Enemies, is Targeter.Allies ->
            "targeter '${targeter::class.simpleName}' which always resolves to no targets"
        is Targeter.Filter -> unsupportedTargeter(targeter.base)
        is Targeter.Random -> unsupportedTargeter(targeter.from)
        else -> null
    }

    /** Mechanics whose [MechanicDispatcher] branch only logs. */
    internal fun isNoOp(mechanic: Mechanic): Boolean = when (mechanic) {
        is Mechanic.Heal, is Mechanic.Resurrect, is Mechanic.RemoveStatus, is Mechanic.Cleanse,
        is Mechanic.Ignite, is Mechanic.Freeze, is Mechanic.Silence,
        is Mechanic.SetVelocity, is Mechanic.Propel, is Mechanic.Leap,
        is Mechanic.SummonLightning, is Mechanic.Summon, is Mechanic.SetBlock,
        is Mechanic.RemoveBlock, is Mechanic.Explode,
        is Mechanic.GiveItem, is Mechanic.ConsumeItem, is Mechanic.ModifyAttribute,
        is Mechanic.SetCooldown,
        is Mechanic.SpawnDisplay, is Mechanic.ScheduleParticleAnimation, is Mechanic.Glow,
        is Mechanic.CameraShake,
        is Mechanic.SendMessage, is Mechanic.SendActionBar, is Mechanic.SendTitle -> true
        is Mechanic.Damage, is Mechanic.ApplyStatus, is Mechanic.Knockback, is Mechanic.Knockup,
        is Mechanic.Teleport, is Mechanic.SpawnParticle, is Mechanic.PlaySound,
        is Mechanic.CastSkill, is Mechanic.IfThen, is Mechanic.Loop, is Mechanic.Choose -> false
    }

    private const val CEL_MARKER: String = "Cel("
}
