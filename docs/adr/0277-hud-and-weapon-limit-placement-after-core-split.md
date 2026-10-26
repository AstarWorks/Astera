---
status: Accepted
date: 2026-10-26
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0277: HUD, weapon-limit and cosmetics placement after the core split

## Status

Accepted. Amends [ADR-0265](0265-core-kernel-and-feature-library-split.md) (and
`docs/architecture/core-module-split.md`) for the placements below. ADR-0265's body stays immutable; where the two
differ on these types, this ADR wins. ADR-0265 is not superseded as a whole.

## Context

ADR-0265 and the split document say the gameplay library owns the HUD layout records. When origin/main's split was
merged with the integration branch the code settled differently, for dependency reasons:

- `kernel:paper`'s `PaperPlayer.sendActionBar` routes through the HUD gateway, and the kernel must not depend on
  `core:feature-gameplay`.
- `WeaponCategory`, `WeaponLimits` and `IWeaponLimitProvider` reference `ItemSpec`, which lives in gameplay.
- The cosmetics services would form a cycle with gameplay if they stayed beside the cosmetic contents.

## Decision

Actual placements (checked against the tree):

| Type | Module |
|---|---|
| `HudLayout`, `HudChannels` | `:kernel:domain` (`core.domain.hud`) |
| `IHudGateway` | `:kernel:runtime` (`core.application.port.outbound`) |
| `WeaponCategory`, `WeaponLimits` | `:core:feature-gameplay` (`core.domain.item`) |
| `IWeaponLimitProvider` | `:core:feature-gameplay` (`core.application.port.outbound.item`) |
| `Cosmetic` (domain) | `:kernel:domain` |
| Cosmetic ports (`CosmeticPorts.kt`) | `:kernel:runtime` |
| `CosmeticService`, `InMemoryCosmeticLoadoutRepository`, `PaperCosmeticDisplay` | `:core:feature-social` |
| `CosmeticContents`, `CosmeticYamlConfig`, `PaperCosmeticEffectPlayer` | `:core:feature-gameplay` |

Rule behind it: anything a kernel module needs at its edge (here the HUD gateway and layout) lives in the kernel;
anything that needs `ItemSpec` lives in gameplay; services that gameplay consumes sit in a library below it
(`feature-social`) so no cycle forms.

## Consequences

- `kernel:paper` can implement `sendActionBar` without depending on a feature library.
- `docs/architecture/core-module-split.md` is updated to match the code.
- Package names are unchanged; only the owning Gradle module differs from ADR-0265's wording.
