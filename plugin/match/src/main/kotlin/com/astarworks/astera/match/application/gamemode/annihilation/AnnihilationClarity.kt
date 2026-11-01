package com.astarworks.astera.match.application.gamemode.annihilation

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.domain.event.PlayerRespawned
import com.astarworks.astera.core.domain.nexus.NexusId
import com.astarworks.astera.core.domain.nexus.NexusPhase
import com.astarworks.astera.core.domain.presentation.PresentationMoments
import com.astarworks.astera.core.domain.nexus.NexusPillarTier
import com.astarworks.astera.core.domain.nexus.NexusState
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.application.gamemode.siege.ObjectiveKit
import com.astarworks.astera.match.application.gamemode.siege.SiegeEnv
import com.astarworks.astera.match.application.port.outbound.IMatchPhaseClock
import com.astarworks.astera.match.application.port.outbound.ObjectiveBarColor
import com.astarworks.astera.match.application.service.OreNodeService

/**
 * What tells a player in Annihilation where the pillars are and what to do: a floating label over every standing pillar
 * (its owner, tier and HP, per viewer ours / theirs) and the hologram + action-bar guide pointing at the nearest enemy
 * pillar of the team's current tier, or at our own pillar while it is being hit. The guide is shown only while the player
 * holds the navigation compass, which sits locked in hotbar slot 9 (index 8) from match start and after every respawn. Runs once a second beside
 * [AnnihilationGameMode]'s own tick.
 *
 * ADR-0319 phase flow: every phase change shows a title + sound to all participants, the objective boss bar carries the phase
 * name and the time to the next phase, and each player gets one "what to do next" line (objective action bar, compass target
 * alike): defend an attacked own pillar, mine ore while the Norma level is low, else attack the weakest enemy pillar.
 */
public class AnnihilationClarity(
    private val env: SiegeEnv,
    private val tracker: AnnihilationMatchTracker,
    private val phases: IMatchPhaseClock,
    /** The Norma level of a player (null = unknown, treated as 1). */
    private val levelOf: (PlayerId) -> Int?,
    /** The ore ladder of the match's stage: which Norma level unlocks which ore (one entry per ore node group). */
    private val oreLadder: (Match) -> List<OreStep>,
) {

    /** [itemId] (its drop) becomes minable at Norma [level]. */
    public data class OreStep(val level: Int, val itemId: String)

    /** One player's "what next": the hint [key] and its [params], and where the compass should point (null = text only). */
    private data class Goal(val key: MessageKey, val params: Map<String, String>, val target: Vec3?)

    private class Watch {
        var phase: NexusPhase? = null
        var ticks: Int = 0
        val hp: MutableMap<NexusId, Int> = mutableMapOf()
        val attacked: MutableMap<TeamId, Pair<Long, Vec3>> = mutableMapOf()
        val labelled: MutableSet<NexusId> = mutableSetOf()
    }

    private val kit = ObjectiveKit(env, MODE, compassItem = true)
    private val watches: MutableMap<MatchId, Watch> = mutableMapOf()
    private val respawns: MutableMap<MatchId, IDomainEventBus.Subscription> = mutableMapOf()

    public fun start(matchId: MatchId) {
        watches[matchId] = Watch()
        respawns.remove(matchId)?.cancel()
        respawns[matchId] = env.eventBus.subscribe(PlayerRespawned::class) { ev ->
            if (ev.matchId == matchId) env.compass()?.give(ev.playerId)
        }
        tick(matchId)
    }

    public fun tick(matchId: MatchId) {
        val watch = watches[matchId] ?: return
        val match = env.matches.findById(matchId) ?: return
        val now = env.now()
        giveCompasses(match)
        val views = tracker.pillarViews(matchId).filter { it.site != null && it.world != null }
        for (view in views) {
            val nexus = view.nexus
            val before = watch.hp.put(nexus.id, nexus.hp)
            if (before != null && nexus.hp < before) watch.attacked[nexus.owningTeam] = now to view.site!!.centre
        }
        placeLabels(match, watch, views)
        if (match.phase == MatchPhase.ACTIVE || match.phase == MatchPhase.SUDDEN_DEATH) {
            val snapshot = phases.current(matchId)
            if (snapshot != null) {
                if (watch.phase != snapshot.phase) {
                    watch.phase = snapshot.phase
                    announce(match, snapshot.phase)
                }
                showBar(match, snapshot.phase, snapshot.remainingTicks, snapshot.phaseTicks)
            }
            watch.ticks++
            guide(match, watch, views, now, snapshot?.phase ?: NexusPhase.Phase1Build)
        }
    }

    public fun end(matchId: MatchId) {
        respawns.remove(matchId)?.cancel()
        watches.remove(matchId) ?: return
        env.labels()?.clearMatch(matchId)
        val members = env.matches.findById(matchId)?.teams?.flatMap { it.members }.orEmpty()
        kit.endMatch(matchId, members)
        env.compass()?.let { compass -> members.forEach(compass::remove) }
    }

    /** Everyone in the match owns the compass (idempotent: covers match start, late joiners and any respawn path). */
    private fun giveCompasses(match: Match) {
        val compass = env.compass() ?: return
        for (team in match.teams) for (member in team.members) compass.give(member)
    }

    private fun placeLabels(match: Match, watch: Watch, views: List<AnnihilationMatchTracker.PillarView>) {
        val labels = env.labels() ?: return
        for (view in views) {
            val nexus = view.nexus
            val key = "pillar-${nexus.id.value}"
            if (nexus.state == NexusState.DESTROYED) {
                if (watch.labelled.remove(nexus.id)) labels.remove(match.id, key)
                continue
            }
            watch.labelled += nexus.id
            val variants = mutableMapOf<String, MutableSet<PlayerId>>()
            for (team in match.teams) for (member in team.members) {
                val mine = team.id == nexus.owningTeam
                val labelKey = when {
                    mine -> "pillar.ours"
                    nexus.state != NexusState.VULNERABLE -> "pillar.enemy-locked"
                    nexus.tier == NexusPillarTier.TIER_3 -> "pillar.enemy-core"
                    else -> "pillar.enemy"
                }
                val params = mapOf(
                    "c" to ObjectiveKit.tagOf(ObjectiveKit.colorOf(nexus.owningTeam)),
                    "tier" to nexus.tier.ordinalValue.toString(),
                    "hp" to nexus.hp.toString(),
                    "max" to nexus.maxHp.toString(),
                )
                val text = env.messages.render(member, MessageKey("astera.label.$labelKey"), params)
                if (text.isBlank() || text.startsWith("astera.")) continue
                variants.getOrPut(text) { mutableSetOf() } += member
            }
            val at = view.site!!.centre
            labels.show(match.id, key, view.world!!, Vec3(at.x, at.y + LABEL_LIFT, at.z), variants)
        }
    }

    /** Title + sound of the phase that just began, to every participant (its own hint line under the title). */
    private fun announce(match: Match, phase: NexusPhase) {
        val moment = if (phase == NexusPhase.Phase5SuddenDeath) PresentationMoments.ANNIHILATION_FINAL else PresentationMoments.ANNIHILATION_PHASE
        for (team in match.teams) for (member in team.members) {
            val args = mapOf(
                "n" to phase.number.toString(),
                "name" to env.messages.render(member, MessageKey("astera.annihilation.phase.${phase.id}.name")),
                "hint" to env.messages.render(member, MessageKey("astera.annihilation.phase.${phase.id}.hint")),
            )
            kit.moment(moment, member, args)
        }
    }

    /** The boss bar: phase name and the time to the next phase (the match end in the last phase); the bar drains as the phase runs out. */
    private fun showBar(match: Match, phase: NexusPhase, remaining: Long, phaseTicks: Long) {
        val progress = if (phaseTicks <= 0L) 0.0 else remaining.toDouble() / phaseTicks
        val color = when (phase) {
            NexusPhase.Phase1Build -> ObjectiveBarColor.GREEN
            NexusPhase.Phase2Engage -> ObjectiveBarColor.BLUE
            NexusPhase.Phase3Center -> ObjectiveBarColor.YELLOW
            NexusPhase.Phase4Escalation, NexusPhase.Phase5SuddenDeath -> ObjectiveBarColor.RED
        }
        val key = MessageKey(if (phase == NexusPhase.Phase5SuddenDeath) "astera.annihilation.bar.final" else "astera.annihilation.bar")
        for (team in match.teams) for (member in team.members) {
            val params = mapOf(
                "n" to phase.number.toString(),
                "name" to env.messages.render(member, MessageKey("astera.annihilation.phase.${phase.id}.name")),
                "time" to OreNodeService.formatClock(remaining),
            )
            kit.statusBar(member, key, params, progress, color)
        }
    }

    private fun guide(match: Match, watch: Watch, views: List<AnnihilationMatchTracker.PillarView>, now: Long, phase: NexusPhase) {
        val ladder = oreLadder(match)
        val textTurn = watch.ticks % TEXT_EVERY_TICKS == 0
        for (team in match.teams) {
            val hit = watch.attacked[team.id]?.takeIf { now - it.first <= ATTACK_WINDOW_TICKS }
            val weakest = weakestEnemy(team.id, views)
            val home = views.filter { it.nexus.owningTeam == team.id && it.nexus.state != NexusState.DESTROYED && it.site != null }
                .minByOrNull { it.nexus.tier.ordinalValue }?.site?.centre
            for (member in team.members) {
                val goal = goalOf(member, phase, hit?.second, weakest, home, ladder)
                if (goal == null) {
                    kit.playerDown(member)
                    continue
                }
                val held = env.compass()?.isHeld(member)
                val target = goal.target
                when {
                    target != null && held != false -> kit.compass(member, target, goal.key, goal.params)
                    else -> {
                        env.guide()?.hide(member)
                        if (textTurn) kit.toast(member, goal.key, goal.params + textOnly(member, target), TEXT_TTL_TICKS)
                    }
                }
            }
        }
    }

    /** Defend first, then mine while the Norma level is low, then attack the weakest enemy pillar. */
    private fun goalOf(
        player: PlayerId,
        phase: NexusPhase,
        hit: Vec3?,
        weakest: AnnihilationMatchTracker.PillarView?,
        home: Vec3?,
        ladder: List<OreStep>,
    ): Goal? {
        if (hit != null) return Goal(HINT_DEFEND, emptyMap(), hit)
        val level = levelOf(player) ?: 1
        val next = ladder.filter { it.level > level }.minByOrNull { it.level }
        if (phase == NexusPhase.Phase1Build || (level < LOW_LEVEL && next != null)) {
            if (next == null) return Goal(HINT_MINE_MAX, emptyMap(), home)
            val ore = env.messages.render(player, MessageKey("astera.annihilation.ore.name.${next.itemId}"))
            return Goal(HINT_MINE, mapOf("ore" to ore, "lv" to next.level.toString()), home)
        }
        val enemy = weakest ?: return null
        val key = when (phase) {
            NexusPhase.Phase3Center -> HINT_ATTACK_CENTER
            NexusPhase.Phase5SuddenDeath -> HINT_SUDDEN
            else -> HINT_ATTACK
        }
        val params = mapOf(
            "team" to env.messages.render(player, MessageKey("astera.match.team.${enemy.nexus.owningTeam.value}")),
            "tier" to enemy.nexus.tier.ordinalValue.toString(),
        )
        return Goal(key, params, enemy.site?.centre)
    }

    /** `{arrow}` / `{dist}` for the plain-text line shown while the compass is not in hand (no arrow, distance when known). */
    private fun textOnly(player: PlayerId, target: Vec3?): Map<String, String> {
        val from = kit.positionOf(player)
        val dist = if (from != null && target != null) ObjectiveKit.flatDistance(from, target).toInt().toString() else "?"
        return mapOf("arrow" to "", "dist" to dist)
    }

    /** The standing, attackable enemy pillar with the least HP (the cheapest one to take down). */
    private fun weakestEnemy(team: TeamId, views: List<AnnihilationMatchTracker.PillarView>): AnnihilationMatchTracker.PillarView? =
        views.filter { it.nexus.owningTeam != team && it.nexus.state == NexusState.VULNERABLE && it.site != null }
            .minByOrNull { it.nexus.hp }

    private companion object {
        const val MODE = "annihilation"
        const val LABEL_LIFT = 2.2
        const val ATTACK_WINDOW_TICKS = 200L
        const val LOW_LEVEL = 2
        const val TEXT_EVERY_TICKS = 3
        const val TEXT_TTL_TICKS = 70
        val HINT_ATTACK = MessageKey("astera.objective.hint.annihilation.attack")
        val HINT_ATTACK_CENTER = MessageKey("astera.objective.hint.annihilation.attack-center")
        val HINT_SUDDEN = MessageKey("astera.objective.hint.annihilation.sudden")
        val HINT_MINE = MessageKey("astera.objective.hint.annihilation.mine")
        val HINT_MINE_MAX = MessageKey("astera.objective.hint.annihilation.mine-max")
        val HINT_DEFEND = MessageKey("astera.objective.hint.annihilation.defend")
    }
}
