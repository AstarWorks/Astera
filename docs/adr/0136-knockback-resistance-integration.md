---
status: Accepted
date: 2026-05-31
deciders: Astera core
supersedes: null
superseded_by: null
---

# ADR-0136: Knockback resistance integration — gateway-boundary contract

## Status

Accepted

## Context

Round 17 audit (3 parallel Explore agents pre-Wave-6 abstraction
verification) initially flagged `ItemAttributes.knockbackResistance` as a
silent feature drop — declared + validated in
`plugin/core/.../domain/item/ItemAttributes.kt`, mapped through the YAML
DTO, but allegedly "NOT consumed anywhere in combat pipeline". Direct
verification proved the agent partially wrong:

- `PaperItemMaterializer.kt:220` DOES consume the field, setting a
  Bukkit `AttributeModifier` for `Attribute.KNOCKBACK_RESISTANCE` on the
  ItemMeta when an item with `knockback_resistance` is equipped.
- Bukkit's vanilla knockback math automatically aggregates these
  per-item modifiers into `target.getAttribute(KNOCKBACK_RESISTANCE).value`
  and scales vanilla-fired knockback accordingly.

The TRUE gap is more subtle: Astera's **custom knockback path** bypasses
Bukkit's vanilla physics entirely.

- `KnockbackModifier` (domain) computes a base impulse vector.
- `ApplyDamageUseCase` hands it to `IKnockbackGateway.applyImpulse(target, impulse)`.
- Wave 6a's `PaperKnockbackGateway` (TBD) will call
  `Player.setVelocity(BukkitVec)` directly — which **does NOT consult**
  `Attribute.KNOCKBACK_RESISTANCE` (the vanilla aggregation is done by
  vanilla's hit handler, not by `setVelocity` itself).
- Therefore: an item equipped with `knockback_resistance: 0.5` would
  scale vanilla-fired knockback by 0.5× (correct) but Astera-fired
  knockback by 1.0× (full impulse — the resistance is silently ignored).

For Phase 1 PoC closing this is invisible because `PaperKnockbackGateway`
does not exist yet — `NoOpKnockbackGateway` (the default Phase 1 binding)
is a log-only stub. The gap will become a real bug the moment Wave 6a
ships the Paper adapter without an explicit resistance-consultation
contract.

## Decision

Establish the **gateway-boundary contract** for knockback resistance.
Resistance lives at the adapter (gateway) layer, not the domain pipeline
layer, because:

1. The authoritative source of resistance differs per adapter:
   `PaperKnockbackGateway` reads Bukkit's already-aggregated
   `Attribute.KNOCKBACK_RESISTANCE`; a non-Paper adapter (e.g. headless
   simulation, Fabric port) would need a different source.
2. Astera's domain pipeline (`KnockbackModifier`) intentionally stops at
   "compute the base impulse vector"; per-target physical state
   (equipped items, status effects, on-fire wind buffer, etc.) belongs
   at the I/O boundary.
3. The existing modifier-chain architecture (`HeadshotModifier` →
   `ArmorReductionModifier` → `CriticalStrikeModifier` →
   `KnockbackModifier`) is concerned with **damage** computation; adding
   a `KnockbackResistanceModifier` 5th in the chain would require domain
   to know about equipped-item attribute aggregation, which lives at the
   Paper adapter (`PaperItemMaterializer.kt:220`).

The contract is documented in 3 places:

### 1. KDoc on `IKnockbackGateway.applyImpulse(...)` (load-bearing)

> Implementations MUST consult the target's effective knockback
> resistance (0..1 fraction) and scale the impulse before applying.
>
> - PaperKnockbackGateway (Wave 6a): read
>   `target.getAttribute(Attribute.KNOCKBACK_RESISTANCE)?.value` —
>   Bukkit already aggregates equipped-item
>   `ItemAttributes.knockbackResistance` into this attribute (via
>   `PaperItemMaterializer`), so the gateway gets the authoritative
>   Phase 1 source for free.
> - Future Astera-native source: read
>   `defenderContainer.effectiveNumber(Attributes.RESIST_KNOCKBACK)`
>   if a feature wave wires the equip-sync use case for
>   non-Bukkit-backed deployments.
>
> Scaling formula: `scaledImpulse = impulse * (1.0 - resistance.coerceIn(0.0, 1.0))`.
> Resistance 1.0 zeroes the impulse (full immunity). Resistance 0.0 leaves
> the impulse unchanged. Implementations MUST cap at `[0.0, 1.0]` so
> configuration errors cannot invert the impulse vector.
>
> `NoOpKnockbackGateway` is exempt (no physical write → reading
> resistance is wasted work).

### 2. Forward-compat `RESIST_KNOCKBACK` AttributeKey

Added to `plugin/core/.../domain/attribute/Attributes.kt`:

```kotlin
public val RESIST_KNOCKBACK: AttributeKey = AttributeKey("resist.knockback")
```

Phase 1 sources resistance from Bukkit's `GENERIC_KNOCKBACK_RESISTANCE`
(populated by `PaperItemMaterializer` at equip time). The Astera-side
container key is reserved for future feature waves that may want to
sync `ItemAttributes.knockbackResistance` directly (parallels Wave 5e
`RESIST_PHYSICAL/etc.` equip-sync pattern via `ApplyArmorModifiersUseCase`).
Having the key in the namespace today means future waves don't need
ADR-level approval to add the sync.

### 3. KDoc on `NoOpKnockbackGateway` documenting the exemption

Makes the contract-vs-no-op distinction explicit so a future contributor
reading the stub does not misinterpret it as a missed contract.

### Konsist guard deferred to Wave 6a

A Konsist rule of the form "if `*.adapter.paper.*` contains a class
`PaperKnockbackGateway`, its source MUST contain the token
`KNOCKBACK_RESISTANCE`" would mechanically enforce the contract. But
since `PaperKnockbackGateway` does not exist yet, the rule would have
nothing to assert against. Land the rule **alongside** the Wave 6a
adapter, not in this wave, so the rule is born already-green.

## Consequences

### Positive

- Wave 6a author has an unambiguous, KDoc-load-bearing contract for
  `PaperKnockbackGateway` resistance integration. The Bukkit attribute
  source is named explicitly; the scaling formula is provided.
- Forward-compat `RESIST_KNOCKBACK` key is in the namespace, ready for
  future Astera-native equip-sync use cases (no name-bikeshedding when
  the time comes).
- `NoOpKnockbackGateway` exemption is explicit, preventing a future
  contributor from mistakenly "fixing" the stub to read attributes that
  don't exist in unit-test scopes.
- No new domain modifier introduced — the modifier chain stays focused
  on damage computation; physical state aggregation stays at the
  adapter boundary where it belongs.

### Negative / Trade-offs

- The contract is enforced by KDoc + future Konsist rule (deferred to
  Wave 6a). A Wave 6a author who ignores the KDoc and skips the
  resistance read would ship a silent-drop bug; the deferred Konsist
  rule mitigates this risk but doesn't eliminate the window.
- Knockback resistance is split across two potential sources (Bukkit
  `Attribute.KNOCKBACK_RESISTANCE` for Phase 1; Astera
  `RESIST_KNOCKBACK` container for future deployments). The split is
  intentional but creates a small "which source wins" question if both
  are populated simultaneously. PaperKnockbackGateway (Wave 6a) should
  prefer Bukkit (already aggregated by Bukkit's own physics path);
  hypothetical future non-Bukkit gateways prefer Astera container.

### Mitigations

- Land Konsist guard in Wave 6a alongside `PaperKnockbackGateway` (rule
  56 candidate — text-scan for `KNOCKBACK_RESISTANCE` token in the
  Paper-side knockback gateway class).
- The "which source wins" question is documented in the IKnockbackGateway
  KDoc (Bukkit Phase 1, Astera container future) so each adapter author
  reads the same contract.
- Phase 1 PoC has no live `PaperKnockbackGateway`, so the contract
  exists before any production code can violate it.

## Alternatives Considered

- **Alt A: New `KnockbackResistanceModifier` 5th in the DamageRule
  pipeline** (per Explore agent recommendation): rejected. Requires
  domain to know about per-target equipped-item attribute aggregation,
  which is an adapter concern. Also requires either a new equip-sync
  use case (`ApplyKnockbackResistanceModifiers`) to populate
  `RESIST_KNOCKBACK` from item attributes, OR a new port to walk
  equipped items at compute time — both expand scope significantly for
  no architectural win over the gateway-boundary approach.
- **Alt B: Add `knockbackResistance` field to `ArmorSpec`** and extend
  `ApplyArmorModifiersUseCase` to sync it: rejected. Resistance lives on
  `ItemAttributes` (item-level, can be on weapons, armor, or accessories),
  not specifically on armor. Adding it to ArmorSpec would duplicate
  the field and require keeping two sources in sync.
- **Alt C: Read resistance per-attempt by walking equipped items in
  `KnockbackModifier`**: rejected. `KnockbackModifier` only has access to
  `IAttributeContainer` (Wave 5e), not equipped item registry. Adding a
  port for "walk equipped items" would duplicate
  `ApplyArmorModifiersUseCase`'s equip-sync work.
- **Alt D: Document the contract only in ADR (no KDoc / no code touch)**:
  rejected. Wave 6a author reads the source file, not the ADR archive.
  KDoc-load-bearing contracts are more likely to be followed.

## References

- 関連 ADR:
  [[adr/0022-multi-plugin-layout]],
  [[adr/0030-damage-pipeline-and-result-error]] (DamageRule + modifier
  chain design — KnockbackModifier is per-Wave-5a part of this chain),
  [[adr/0058-universal-attribute-container]] (Wave 5e — established the
  `RESIST_*` container key pattern this ADR extends with `RESIST_KNOCKBACK`),
  [[adr/0073-item-as-first-class-content]] (Wave _items-1 — ItemAttributes
  hosts the source field),
  [[adr/0133-pre-paper-port-fill]] (Wave Q — same Round 17 spirit:
  pre-Paper-sweep abstraction-fill)
- Related files:
  `plugin/combat/src/main/kotlin/.../application/port/outbound/IKnockbackGateway.kt`
  (KDoc-load-bearing contract),
  `plugin/combat/src/main/kotlin/.../application/service/NoOpKnockbackGateway.kt`
  (exemption documented),
  `plugin/combat/src/main/kotlin/.../domain/rule/KnockbackModifier.kt`
  (computes pre-resistance impulse — unchanged by this ADR),
  `plugin/items/src/main/kotlin/.../adapter/paper/PaperItemMaterializer.kt:220`
  (Phase 1 source: sets Bukkit `Attribute.KNOCKBACK_RESISTANCE` from
  `ItemAttributes.knockbackResistance`),
  `plugin/core/src/main/kotlin/.../domain/attribute/Attributes.kt`
  (forward-compat `RESIST_KNOCKBACK` key added)
- Future work:
  - **Wave 6a**: `PaperKnockbackGateway` impl — MUST read
    `target.getAttribute(Attribute.KNOCKBACK_RESISTANCE)?.value`, scale
    impulse per formula in IKnockbackGateway KDoc, then call
    `Player.setVelocity()`. Land Konsist Rule 56 in the same wave.
  - **Future feature wave** (if needed): `ApplyKnockbackResistanceModifiers`
    use case parallel to `ApplyArmorModifiersUseCase` — syncs
    `ItemAttributes.knockbackResistance` from equipped items into
    wearer's `Attributes.RESIST_KNOCKBACK` container slot for
    non-Bukkit-backed deployments.
