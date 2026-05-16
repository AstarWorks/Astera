package com.astarworks.astera.domain.model.skill

/**
 * Input action that fires a skill. Modeled as a vendor-neutral enum so the
 * domain never references Bukkit's `Action` or PlayerInteractEvent shapes.
 *
 * `HOLD` is a sustained press (channel-style skills); `AUTO` is invoked by
 * scheduled triggers rather than player input.
 */
public enum class SkillTrigger {
    LEFT_CLICK,
    RIGHT_CLICK,
    SNEAK_LEFT,
    SNEAK_RIGHT,
    HOLD,
    AUTO,
}
