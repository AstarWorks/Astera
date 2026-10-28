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
import com.astarworks.astera.core.domain.nexus.NexusPillarTier
import com.astarworks.astera.core.domain.nexus.NexusState
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.application.gamemode.siege.ObjectiveKit
import com.astarworks.astera.match.application.gamemode.siege.SiegeEnv

/**
 * What tells a player in Annihilation where the pillars are and what to do: a floating label over every standing pillar
 * (its owner, tier and HP, per viewer ours / theirs) and the hologram + action-bar guide pointing at the nearest enemy
 * pillar of the team's current tier, or at our own pillar while it is being hit. The guide is shown only while the player
 * holds the navigation compass, which sits locked in hotbar slot 9 (index 8) from match start and after every respawn. Runs once a second beside
 * [AnnihilationGameMode]'s own tick; knows nothing about phases beyond what the pillars' state says.
 */
public class AnnihilationClarity(private val env: SiegeEnv, private val tracker: AnnihilationMatchTracker) {

    private class Watch {
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
        if (match.phase == MatchPhase.ACTIVE || match.phase == MatchPhase.SUDDEN_DEATH) guide(match, watch, views, now)
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

    private fun guide(match: Match, watch: Watch, views: List<AnnihilationMatchTracker.PillarView>, now: Long) {
        for (team in match.teams) {
            val hit = watch.attacked[team.id]?.takeIf { now - it.first <= ATTACK_WINDOW_TICKS }
            val enemy = enemyTargets(team.id, views)
            for (member in team.members) {
                when {
                    hit != null -> kit.compass(member, hit.second, HINT_DEFEND)
                    enemy.isNotEmpty() -> kit.nearest(member, enemy.map { it.second })?.let { spot ->
                        kit.compass(member, spot, HINT_ATTACK, mapOf("tier" to enemy.first { it.second == spot }.first.toString()))
                    }
                    else -> kit.playerDown(member)
                }
            }
        }
    }

    /** Each rival team's lowest standing tier: the pillars [team] is supposed to be attacking right now, as (tier, centre). */
    private fun enemyTargets(team: TeamId, views: List<AnnihilationMatchTracker.PillarView>): List<Pair<Int, Vec3>> {
        val standing = views.filter { it.nexus.owningTeam != team && it.nexus.state != NexusState.DESTROYED }
        val open = standing.groupBy { it.nexus.owningTeam }.values.flatMap { theirs ->
            val tier = theirs.minOf { it.nexus.tier.ordinalValue }
            theirs.filter { it.nexus.tier.ordinalValue == tier }
        }
        return open.sortedBy { it.nexus.tier.ordinalValue }.map { it.nexus.tier.ordinalValue to it.site!!.centre }
    }

    private companion object {
        const val MODE = "annihilation"
        const val LABEL_LIFT = 2.2
        const val ATTACK_WINDOW_TICKS = 200L
        val HINT_ATTACK = MessageKey("astera.objective.hint.annihilation.attack")
        val HINT_DEFEND = MessageKey("astera.objective.hint.annihilation.defend")
    }
}
