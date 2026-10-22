package com.astarworks.astera.core.domain.loginbonus

/**
 * The outcome of a successful daily claim (ADR-0210).
 *
 * @property streakDays consecutive login days including today (1 after a gap).
 * @property day the rotation day whose reward was paid (`1..` the table length; the table wraps).
 */
public data class LoginBonusClaim(
    public val streakDays: Int,
    public val day: Int,
    public val reward: LoginBonusReward,
)

/** Read-only view for `/astera daily` and the lobby UI. */
public data class LoginBonusStatus(
    public val claimedToday: Boolean,
    /** Streak as it will stand once today is claimed (or already stands, when [claimedToday]). */
    public val streakDays: Int,
    /** Rotation day of today's reward (the one just paid when [claimedToday]). */
    public val day: Int,
    public val reward: LoginBonusReward?,
    /** Reward of the following rotation day (tomorrow's), when one is configured. */
    public val tomorrow: LoginBonusReward?,
)
