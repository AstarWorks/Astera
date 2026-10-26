package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.mob.boss.BossId
import com.astarworks.astera.core.domain.mob.runtime.MobId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.social.PartyId
import java.time.Instant

/**
 * Published by `IBossLifecycleService` when a boss instance is killed
 * (Wave 5pve-boss, ADR-0098).
 *
 * Distinct from [MobKilled] — boss kills are encounter-level events with
 * party-attributed credit, deterministic loot rolls, and audit-table
 * persistence. Subscribers that only care about generic mob kills (Norma
 * progression "kill 10 zombies") listen to [MobKilled]; subscribers that
 * care about boss-specific milestones ("first clear", "weekly boss kill")
 * listen to this event.
 *
 * Subscribers:
 *  - `IPveLootDistributor` (boss-aware impl): roll [com.astarworks.astera.core.domain.mob.boss.BossSpec.lootTable]
 *    via `ILootRollService` and grant.
 *  - Achievement / advancement system: unlock "First Kill" / "Weekly Boss" cards.
 *  - V30 `boss_kills` audit writer: persist `(boss_id, party_id, killer, fight_ticks)`.
 *  - Announcer: broadcast "<Party> has felled <Boss>".
 *  - HUD: dismiss the raid bar.
 *
 * @property mobId Live boss instance that died.
 * @property bossId Registry id of the [com.astarworks.astera.core.domain.mob.boss.BossSpec].
 * @property killerId Player credited with the killing blow. Null only for
 *   environmental / suicide death (rare in raid context).
 * @property partyId Raid party that owned the encounter. Null iff the
 *   boss's [com.astarworks.astera.core.domain.mob.raid.RaidLockPolicy] was
 *   [com.astarworks.astera.core.domain.mob.raid.RaidLockPolicy.Open] (e.g.
 *   admin spawn / tutorial).
 * @property fightDurationTicks Server ticks from spawn to death. Useful for
 *   speedrun leaderboards + audit.
 * @property finalPhase Phase the boss was in at death (typically the last
 *   phase). Carried so audit doesn't need to re-walk the spec.
 * @property atTick Server tick at death time.
 */
public data class BossDefeated(
    public val mobId: MobId,
    public val bossId: BossId,
    public val killerId: PlayerId?,
    public val partyId: PartyId?,
    public val fightDurationTicks: Long,
    public val finalPhase: Int,
    public val atTick: Long,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Global

    init {
        require(fightDurationTicks >= 0L) {
            "BossDefeated.fightDurationTicks must be >= 0: $fightDurationTicks"
        }
        require(finalPhase >= 0) {
            "BossDefeated.finalPhase must be >= 0: $finalPhase"
        }
    }
}
