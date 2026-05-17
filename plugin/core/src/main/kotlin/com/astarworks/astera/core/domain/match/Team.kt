package com.astarworks.astera.core.domain.match

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Team identity and current roster within a [Match].
 *
 * Membership is part of [Team] (and therefore part of [Match]) because the
 * domain treats "who is on which team" as part of the match aggregate. Teams
 * persisted independently of a match (e.g. clan teams in Phase 4) would be a
 * separate type.
 */
public data class Team(
    val id: TeamId,
    val displayNameKey: MessageKey,
    val colorKey: MessageKey,
    val members: Set<PlayerId>,
)
