package com.astarworks.astera.core.domain.event

/** Why XP was granted. Pure data; UI maps to display text. */
public sealed interface XpSource {
    public data object Kill : XpSource
    public data object Assist : XpSource
    public data object MatchWin : XpSource
    public data object MatchParticipation : XpSource
    public data object MissionReward : XpSource
    public data object AdminGrant : XpSource
}
