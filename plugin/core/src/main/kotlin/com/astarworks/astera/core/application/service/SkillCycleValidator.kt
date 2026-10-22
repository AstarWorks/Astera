package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.skill.mechanic.Mechanic
import com.astarworks.astera.core.domain.skill.phase.PhaseMechanics

/**
 * Validates that the registered skill graph is acyclic at load time.
 *
 * Wave _skills-2 (ADR-0077) introduces [Mechanic.CastSkill], which lets one
 * skill chain into another. Without this guard, a YAML author can write
 * `skill A → CastSkill(B)`, `skill B → CastSkill(A)` and the dispatcher
 * would recurse infinitely at runtime. The check runs once per registry
 * load and fails fast with the offending skill ids.
 *
 * Algorithm: extract `CastSkill` references from every mechanic (including
 * those nested inside [Mechanic.IfThen], [Mechanic.Loop], [Mechanic.Choose]
 * branches) into a directed graph, then run an **iterative** Tarjan SCC to
 * keep the stack depth bounded regardless of nesting depth. Any SCC of
 * size > 1 — or any node with a self-edge — is a cycle.
 */
public class SkillCycleValidator {

    public fun validate(skills: Map<SkillId, PhaseMechanics>): Result<Unit, SkillCycleError> {
        val edges: Map<SkillId, Set<SkillId>> = skills.mapValues { (_, phases) ->
            collectCastSkillRefs(phases)
        }
        val sccCycles = tarjanCycles(edges)
        return if (sccCycles.isEmpty()) {
            Result.success(Unit)
        } else {
            Result.failure(SkillCycleError(sccCycles))
        }
    }

    // ---- Reference collection ----

    private fun collectCastSkillRefs(phases: PhaseMechanics): Set<SkillId> {
        val refs = LinkedHashSet<SkillId>()
        val lists: List<List<Mechanic>> = when (phases) {
            is PhaseMechanics.Projectile -> listOf(
                phases.cast, phases.hitEntity, phases.hitBlock, phases.miss, phases.expire,
            )
            is PhaseMechanics.Melee -> listOf(phases.cast, phases.hitEntity, phases.miss)
            is PhaseMechanics.Aoe -> listOf(phases.cast, phases.hitEntity, phases.expire)
            is PhaseMechanics.Buff -> listOf(phases.cast, phases.expire, phases.removed)
            is PhaseMechanics.Teleport -> listOf(phases.cast, phases.preTeleport, phases.postTeleport)
            is PhaseMechanics.Sequence -> listOf(phases.cast)
            is PhaseMechanics.Summon -> listOf(phases.cast, phases.spawn, phases.summonDeath)
        }
        for (list in lists) collectCastSkillRefs(list, refs)
        return refs
    }

    private fun collectCastSkillRefs(mechanics: List<Mechanic>, sink: MutableSet<SkillId>) {
        // Iterative walk so deep IfThen / Loop / Choose nesting cannot blow the JVM stack.
        val stack: ArrayDeque<Mechanic> = ArrayDeque()
        for (m in mechanics) stack.addLast(m)
        while (stack.isNotEmpty()) {
            when (val m = stack.removeLast()) {
                is Mechanic.CastSkill -> sink.add(m.skillId)
                is Mechanic.IfThen -> {
                    for (x in m.then_) stack.addLast(x)
                    for (x in m.else_) stack.addLast(x)
                }
                is Mechanic.Loop -> {
                    for (x in m.mechanics) stack.addLast(x)
                }
                is Mechanic.Counter -> {
                    for (x in m.onHit) stack.addLast(x)
                }
                is Mechanic.DashStrike -> {
                    for (x in m.onHit) stack.addLast(x)
                }
                is Mechanic.Choose -> {
                    for (branch in m.weighted) {
                        for (x in branch.mechanics) stack.addLast(x)
                    }
                }
                else -> Unit // leaf — no nested mechanics
            }
        }
    }

    // ---- Iterative Tarjan SCC ----

    /**
     * Returns the SCCs that constitute cycles (size > 1 or a self-loop).
     * Implemented iteratively against an explicit DFS stack so the JVM
     * stack stays bounded.
     */
    private fun tarjanCycles(edges: Map<SkillId, Set<SkillId>>): List<List<SkillId>> {
        val nodes: List<SkillId> = edges.keys.toList()
        if (nodes.isEmpty()) return emptyList()

        val indices = HashMap<SkillId, Int>(nodes.size)
        val lowlink = HashMap<SkillId, Int>(nodes.size)
        val onStack = HashSet<SkillId>(nodes.size)
        val stack = ArrayDeque<SkillId>()
        val cycles = mutableListOf<List<SkillId>>()
        var nextIndex = 0

        // Per-node iterator state for the explicit DFS.
        class Frame(val node: SkillId, val iter: Iterator<SkillId>)

        for (start in nodes) {
            if (start in indices) continue

            val callStack = ArrayDeque<Frame>()
            // Push initial frame.
            indices[start] = nextIndex
            lowlink[start] = nextIndex
            nextIndex++
            stack.addLast(start)
            onStack.add(start)
            callStack.addLast(Frame(start, (edges[start] ?: emptySet()).iterator()))

            while (callStack.isNotEmpty()) {
                val top = callStack.last()
                if (top.iter.hasNext()) {
                    val w = top.iter.next()
                    val wIndex = indices[w]
                    if (wIndex == null) {
                        // Only descend into nodes that participate in the graph.
                        // Unknown skill_id refs are not validator's concern (caller's job).
                        if (w !in edges) continue
                        indices[w] = nextIndex
                        lowlink[w] = nextIndex
                        nextIndex++
                        stack.addLast(w)
                        onStack.add(w)
                        callStack.addLast(Frame(w, (edges[w] ?: emptySet()).iterator()))
                    } else if (w in onStack) {
                        lowlink[top.node] = minOf(lowlink.getValue(top.node), wIndex)
                    }
                } else {
                    // Done with top.node — pop & propagate lowlink.
                    val finishedNode = top.node
                    callStack.removeLast()
                    if (lowlink.getValue(finishedNode) == indices.getValue(finishedNode)) {
                        // Found an SCC root — pop scc off the main stack.
                        val scc = mutableListOf<SkillId>()
                        while (true) {
                            val popped = stack.removeLast()
                            onStack.remove(popped)
                            scc.add(popped)
                            if (popped == finishedNode) break
                        }
                        // Cycle if size > 1 or self-loop on the single node.
                        if (scc.size > 1 || (edges[finishedNode]?.contains(finishedNode) == true)) {
                            cycles.add(scc.toList())
                        }
                    }
                    if (callStack.isNotEmpty()) {
                        val parent = callStack.last().node
                        lowlink[parent] = minOf(lowlink.getValue(parent), lowlink.getValue(finishedNode))
                    }
                }
            }
        }
        return cycles
    }
}

/**
 * One or more skill-id chains that form a cycle through `Mechanic.CastSkill`.
 *
 * Each [cycles] entry is the list of skill ids in one SCC of size > 1, or a
 * single-element list when the skill self-casts. The application layer
 * surfaces these to the YAML loader as a hard load failure.
 */
public data class SkillCycleError(public val cycles: List<List<SkillId>>) {
    init { require(cycles.isNotEmpty()) { "SkillCycleError.cycles must not be empty" } }
}
