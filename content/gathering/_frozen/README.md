# FROZEN (ECO-06, 2026-10-26)

Gathering spots, crafting recipes, the `item-collection` quest and the PvE drop tables are
**frozen**: loaders read only the top level of their directory, so everything under `_frozen/` is
inert on purpose.

Why: there is no `survival-default` world (`content/worlds/` has only the lobby), crafting needs an
`AsteraWorkbench` station that cannot be reached, `iron-ingot` and the drop-table materials
(`dust-of-ember`, `imp-claw`, `wolf-tooth`, ...) have no item definitions, and nothing consumes
`LootDropped`. Shipping them produced dead quests and drops that resolved to nothing.

Frozen locations: `content/gathering/_frozen/`, `content/recipes/_frozen/`,
`content/quests/_frozen/item-collection.yaml`, `content/pve/drop-tables/_frozen/`.

Revive: move the files back up one level once the survival world exists and the referenced items are
defined in `content/items/` (ShippedWeaponAcquisitionTest also checks that every loaded drop item exists).
