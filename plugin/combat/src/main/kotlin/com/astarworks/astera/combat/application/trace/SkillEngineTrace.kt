package com.astarworks.astera.combat.application.trace

/**
 * Switch for the skill engine v2 cast trace (SKL-08): when on, `SkillEngineTraceObserver` logs the cast lifecycle at INFO
 * (start, every event fired, a summary at the end). Off by default so the server log stays quiet.
 * Toggled by `/astera skill trace on|off`, or at boot by the `ASTERA_SKILL_TRACE` env var / `skill-engine.trace` system property.
 */
public object SkillEngineTrace {
    @Volatile
    public var enabled: Boolean = System.getenv("ASTERA_SKILL_TRACE") != null || System.getProperty("skill-engine.trace") == "true"
}
