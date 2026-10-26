package com.astarworks.astera.core.domain.attribute

/**
 * Stable registry of well-known [AttributeKey]s (ADR-0058 §2).
 *
 * Feature plugins may add their own keys (e.g. Wave 5c's
 * `Attributes.GENERATOR_ENERGY = AttributeKey("generator.energy")`); the
 * namespace prefix prevents collision. The constants here are the ones
 * Core, Combat, and the upcoming Match / HUD code reach for by name.
 *
 * Persistence (Wave 5e V14) backs these JSONB-encoded; the string key is
 * the natural column.
 */
public object Attributes {
    // ---- Stats ---------------------------------------------------------
    /**
     * Astera player max HP when no explicit value was set (item 18, 2026-10-27: 20 -> 100). The Bukkit health bar
     * is a proportional mirror of Astera HP (10 hearts at any max), so this never touches the vanilla attribute.
     */
    public const val DEFAULT_PLAYER_MAX_HP: Double = 100.0

    /**
     * Factor from vanilla Minecraft damage / healing numbers (20 HP scale: fall, fire, mob melee, vanilla-sourced
     * regen) to Astera HP ([DEFAULT_PLAYER_MAX_HP] / 20). Applied only where a vanilla-scale number enters Astera HP.
     */
    public const val VANILLA_HP_SCALE: Double = DEFAULT_PLAYER_MAX_HP / 20.0

    public val MAX_HP: AttributeKey = AttributeKey("stat.max_hp")
    public val CURRENT_HP: AttributeKey = AttributeKey("stat.current_hp")
    public val MOVEMENT_SPEED: AttributeKey = AttributeKey("stat.movement_speed")
    public val ATTACK_DAMAGE: AttributeKey = AttributeKey("stat.attack_damage")

    // ---- Per-attribute resists ----------------------------------------
    // Wave 5e migrates ArmorReduction.perAttribute (Wave 5a-fix transient
    // form) into resist.* keys on the wearer's container.
    public val RESIST_PHYSICAL: AttributeKey = AttributeKey("resist.physical")
    public val RESIST_FIRE: AttributeKey = AttributeKey("resist.fire")
    public val RESIST_ELECTRIC: AttributeKey = AttributeKey("resist.electric")
    public val RESIST_WIND: AttributeKey = AttributeKey("resist.wind")
    public val RESIST_WATER: AttributeKey = AttributeKey("resist.water")
    public val RESIST_EARTH: AttributeKey = AttributeKey("resist.earth")

    // Wave S 5knockback-resistance (ADR-0136): knockback impulse resistance
    // (0..1 fraction). Forward-compat key — Phase 1 sources resistance from
    // Bukkit's GENERIC_KNOCKBACK_RESISTANCE attribute (populated by
    // PaperItemMaterializer when items with `knockback_resistance` are
    // equipped). Future waves may sync ItemAttributes.knockbackResistance
    // directly into this container key for an Astera-side authoritative
    // value (parallels Wave 5e RESIST_PHYSICAL/etc. equip-sync pattern via
    // ApplyArmorModifiersUseCase).
    public val RESIST_KNOCKBACK: AttributeKey = AttributeKey("resist.knockback")

    // ---- Resources -----------------------------------------------------
    public val MANA_CURRENT: AttributeKey = AttributeKey("resource.mana_current")
    public val MANA_MAX: AttributeKey = AttributeKey("resource.mana_max")
    public val MANA_REGEN_PER_SEC: AttributeKey = AttributeKey("resource.mana_regen_per_sec")
    // Wave 5e: tick counter for the regen cadence — formerly ManaPool.lastRegenTick.
    public val MANA_LAST_REGEN_TICK: AttributeKey = AttributeKey("resource.mana_last_regen_tick")

    // ---- Modifiers -----------------------------------------------------
    public val COOLDOWN_REDUCTION: AttributeKey = AttributeKey("modifier.cooldown_reduction")

    // ---- Player stat keys (Wave 5e migration target) ------------------
    // PlayerStats fields become computed getters reading these via
    // `container.effectiveNumber(...)`. The string namespace is what V14
    // stores in `player_attributes.attributes` JSONB.
    public val STAT_KILLS: AttributeKey = AttributeKey("stat.kills")
    public val STAT_DEATHS: AttributeKey = AttributeKey("stat.deaths")
    public val STAT_ASSISTS: AttributeKey = AttributeKey("stat.assists")
    public val STAT_DAMAGE_DEALT: AttributeKey = AttributeKey("stat.damage_dealt")
    public val STAT_DAMAGE_TAKEN: AttributeKey = AttributeKey("stat.damage_taken")
    public val STAT_PLAYTIME_SEC: AttributeKey = AttributeKey("stat.playtime_sec")
    public val STAT_MATCHES_PLAYED: AttributeKey = AttributeKey("stat.matches_played")
    public val STAT_WINS: AttributeKey = AttributeKey("stat.wins")
    public val STAT_LOSSES: AttributeKey = AttributeKey("stat.losses")

    // ---- Wallet currency keys (Wave 5e migration target) --------------
    // Wallet.balance(currency) becomes a computed read of these.
    public val WALLET_GOLD: AttributeKey = AttributeKey("wallet.gold")
    public val WALLET_GEM: AttributeKey = AttributeKey("wallet.gem")
}
