package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.combat.CombatContext
import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.mob.MobTypeId
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Published by `IMobAdapter` / `MobSkillRouter` when a tracked mob dies
 * (Wave 5pve, ADR-0085).
 *
 * The event fires **after** the [com.astarworks.astera.core.domain.mob.MobSkillBinding.OnDeath]
 * binding has been routed, so OnDeath effects observe `MobSkillTriggered` first
 * and [MobKilled] last (death-rattle ordering: cast, then die).
 *
 * Subscribers:
 *  - `IPveLootDistributor` (Phase 4): consults [drops] to spawn loot entities.
 *  - `IPveXpDistributor` (Phase 4): grants XP to [killerId] based on [mobTypeId].
 *  - Combat: clears damage-attribution windows tied to the handle.
 *
 * @property handle Vendor-neutral entity handle being killed.
 * @property mobTypeId Registry id of the dying mob's [com.astarworks.astera.core.domain.mob.MobSpec].
 * @property killerId The player credited with the kill, or `null` for
 *   environmental / suicide deaths (the loot distributor may still spawn
 *   drops in that case; the XP distributor skips).
 * @property drops Pre-resolved item ids the loot distributor *will* spawn
 *   (carrying the resolved list keeps subscribers from re-walking the loot
 *   table). The Phase 4 distributor is the source of truth for resolution.
 * @property atTick Server tick at kill time.
 */
public data class MobKilled(
    public val handle: EntityHandle,
    public val mobTypeId: MobTypeId,
    public val killerId: PlayerId?,
    public val drops: List<ItemId>,
    public val atTick: Long,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now(),
    /** Where it happened (ADR-0237): `Match` only when the killer is a match participant; default is for tests. */
    public val context: CombatContext = CombatContext.Match(),
) : DomainEvent
