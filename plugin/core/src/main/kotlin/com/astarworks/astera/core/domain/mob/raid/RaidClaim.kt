package com.astarworks.astera.core.domain.mob.raid

import com.astarworks.astera.core.domain.mob.runtime.MobId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.social.PartyId
import java.time.Instant

/**
 * Records which party (and which member made the first hit) has claimed an
 * active boss instance (Wave 5pve-boss, ADR-0098).
 *
 * Stored in-memory by the boss lifecycle service for the lifetime of the
 * instance. Cleared on boss death, party disband, or server restart.
 *
 * For [RaidLockPolicy.Open] encounters no claim is recorded — the lifecycle
 * service simply skips ownership checks for those bosses.
 *
 * The [firstHitter] is preserved separately from [partyId] because the
 * `PvP-style` kill-credit logic (which party member made the most damage)
 * is decided at kill time, not claim time. The first hitter is just the
 * "claim trigger" — useful for logging + audit ("party X claimed boss Y
 * because player Z hit first at tick T").
 *
 * @property mobId Live boss instance being claimed.
 * @property partyId Party that owns the encounter.
 * @property firstHitter Specific party member whose damage triggered the
 *   claim.
 * @property claimedAt Wall-clock claim instant — paired with the server
 *   tick recorded on [com.astarworks.astera.core.domain.event.BossSpawned].
 */
public data class RaidClaim(
    public val mobId: MobId,
    public val partyId: PartyId,
    public val firstHitter: PlayerId,
    public val claimedAt: Instant,
)
