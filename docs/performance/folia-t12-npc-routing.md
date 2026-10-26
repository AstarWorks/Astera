# T12: NPC backend and handler ownership

ADR-0264 implementation. The existing Gradle catalog, plugin manifests and Docker downloads already contained no Citizens dependency at this task's base. This change removes the remaining Citizens adapter, selection model, traits and backend-switch event. Accepted ADRs remain historical records.

## NPC lifecycle

`NpcAdapter` is the sole `INpcAdapter`. The body materializer is bound once during Core wiring on every server role; lobby YAML loading remains lobby-only. Creation, teleport/replacement and despawn all use this backend, including non-lobby callers. Headless hosts retain logical handles without world bodies.

- `DisplayNpc` creates a Text/Item/BlockDisplay and an Interaction hitbox on the destination Region. Both entities are non-persistent and claimed by the managed-entity registry. Removal uses each entity's Entity scope. Chunk unload releases body references and claims; chunk entity load re-materializes the logical NPC.
- `PlayerNpc` creates only client packets through PacketEvents: cached texture properties, an unlisted player-info entry and a player spawn packet. Entity IDs come from PacketEvents' shared allocator. Visibility is reconciled on each viewer's Entity scope within 64 blocks; move, removal, respawn, world change and disconnect clear stale state.
- Paper 26.3's separate ATTACK packet and both right-click forms are intercepted for NPC hitboxes and fake players. Clicks are checked on the clicking player's Entity scope for current handle, world and distance (6 blocks). Duplicate right-click forms in the same tick dispatch once. Packet callbacks never inspect a live inventory or location on Netty.

The existing lobby fields, `display_entity` and `armor_stand` content body labels remain accepted; both render using Display bodies. No old runtime SpawnSpec or second decoder remains. New optional fields for `type: player` are `profile_name` (1–16 ASCII profile characters), `texture_value` (required cached texture) and `signature` (optional). There is no profile-name network lookup. Shipped NPC YAML values are unchanged; only the market file's schema comment changed.

## Ownership sweep

The Brigadier bridge already runs console executors on Global and player executors on Entity. The admin command console already selects its viewer's Entity dispatcher. Synchronous Bukkit event cancellation stays in its event callback; ordinary player/inventory events already run on that player's region.

Changed routes:

- Inventory GUI intents dispatch on the clicking player's Entity next tick; GUI open/update/close also dispatch to the viewer when called from another scope. GUI reopen bookkeeping is concurrent across players.
- Admin GUI game-mode changes and kicks run on the target Entity and return feedback to the administrator's Entity. Bring/teleport snapshots the destination on its Entity, then calls teleportAsync on the moving Entity.
- Player/console item-give commands enter the recipient's Entity dispatcher. Refresh fans out over Entity scopes and returns a future with the completed stack count; retired recipients contribute zero.
- Resource-pack reload and server drain fan out per Entity. Training weapon removal on match formation/start does likewise. Training sweeps enumerate IDs before examining inventories on their owners.
- Block writes use the target Region, including negative-coordinate flooring. Sign validation uses its block's Region. Managed helper cleanup, chunk/world listeners, rack teardown, menu-kit stripping and vault close use their owners.

Combat mechanic execution, HUD packets and persistence have concurrent migration tasks (T8b, T9, T10); their internal designs are not replaced here.

## Verification

Passed (compile/build only):

`./gradlew compileKotlin compileTestKotlin :plugin:core:shadowJar :plugin:items:shadowJar`

No tests or live-container operations were executed. Existing Shadow duplicate-license/Netty metadata warnings remain.

Checks committed for the manager's integration run:

- `ShippedLobbyNpcTest`: real content loader, single NPC adapter, entity router and click bindings. Replaces the old fake-body loader test and removes its test-policy exception.
- `PlayEntryE2eTest`: Display/Interaction visibility, right click, separate ATTACK left click, picker and queue flow.
- `LobbyAccessMapE2eTest`: role-hub labels now come from Interaction metadata.
- `CommandsE2eTest#console and player item commands reach a remote player scope`: recipient at x/z 4096; player give, console give and completed inventory refresh.
- Konsist: reject Citizens imports, require exactly one NPC adapter, reject direct player/inventory accesses inside onlinePlayers loops outside kernel unless enclosed in an Entity callback. Obsolete backend-event/test allowlist entries are removed.

Pending runtime evidence: manager's Paper/Folia E2E batch and `build/e2e-report.md`, including cross-region actions and shutdown cleanup. The packet-player skin path has no shipped lobby fixture and still needs a real-client visual check with a valid cached texture. Runtime tests were intentionally not run by this worker.

## 依頼

- `wp/ui-access`: FYI `content/lobby-npcs/market-npc.yaml` has only a corrected schema comment. No shipped field migration is required. Skin-bearing content can use `type: player` and the cached-texture fields above.
- Manager: run the committed E2E/Konsist checks during integration, including the Folia lane; attach the resulting report before marking T12 verified.

## Board 更新案

T12 → merge-ready: implementation and compile/shadowJar evidence above; runtime verification remains pending the manager's integration batch.
