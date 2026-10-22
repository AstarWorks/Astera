---
status: Accepted
date: 2026-10-22
deciders: founder + Claude
supersedes: null
superseded_by: null
---

# ADR-0184: One `/astera` command tree with working execution and tab completion

## Status

Accepted. Supersedes the **command shape** of [[adr/0122-admin-operations-completeness]]
(flat `/astera-<verb>` Brigadier literals, each wrapped by a per-command
`requirePermission(permissionService, ...)` executor decorator). The rest of ADR-0122 (the
admin ports, the join-listener idempotency guard) and the permission/role model of
[[adr/0086-admin-ops-permission-presence-feedback]] are unchanged.

## Context

The founder reported: commands are hyphen-joined roots (`/astera-match`, `/astera-queue`,
`/astera-items`, `/astera-shop`, `/astera-grant-currency`, ...), tab completion does not
work and commands often do not execute. Reading the code found these independent causes:

1. **Nothing was ever allowed to run an admin command.** `IPermissionService` starts with an
   empty role table (no role is assigned at join, not even to server operators) and the
   executor decorator checked only that table, so every gated command answered
   `astera.permission.denied`. `/astera-promote`, the command that would grant a role, was
   itself gated.
2. **Replies never reached the player.** The Paper adapter printed `Failure` results as the
   raw key (`[astera.permission.denied]`); `Success` was silent; many executors only
   `println`/`log.info`ed their output (`feedback list`, `slow-query report`, `friend list`,
   `party list`, `config get`, `health`, `audit`, ...). About 150 message keys that executors
   reply with did not exist in `en.yaml`/`ja.yaml` (the renderer falls back to the key), and
   some builders referenced keys under different spellings (`player_only` vs `player-only`).
3. **Optional arguments could not be omitted.** The DSL put the executor on the deepest
   argument of a chain only, so `/astera-items give <p> <item> [amount]`,
   `/astera-teleport <p> <world> [x y z]`, `/astera-audit <p> [limit]`,
   `/astera-maintenance on [reason]`, `/astera-backup list` and `/astera-shop` (sic) were
   syntax errors whenever the optional tail was left out.
4. **Registration was racy and fragmented.** One `PaperCommandHandler` per server buffered all
   specs and re-registered every root as a plugin command of Astera-Core on `COMMANDS`;
   plugin.yml `commands:` blocks (`astera-match`, `astera-items`, `astera-stats`,
   `astera-economy`) additionally declared legacy Bukkit commands with no executor under the
   same labels; `/astera` itself was registered by a lone `reload` leaf; the
   `tutorial`, `mod history/lift/ratelimit` builders were never mounted at all; ids containing
   `:` (`astera:forest-wolf`) were rejected by Brigadier's `word()`.
5. **Completion data was empty.** Completers existed for only a handful of arguments; the
   "prior arguments" map handed to completers was always empty; player arguments were plain
   strings without suggestions; the queue `join` argument was not suggested and the documented
   `join annihilation` was not even accepted by the parser (`custom:annihilation` only).

## Decision

### One root, contributed subtrees

Core owns the single Brigadier root `astera`. Plugins never register a root. They mount subtrees
through a host-neutral inbound port:

```kotlin
interface IAsteraCommandRegistry {
    fun register(owner: String, parent: List<String>, spec: CommandSpec.Literal): Registration
    fun unregisterOwner(owner: String)
    fun snapshot(): CommandSpec.Literal          // the merged `astera` root
    fun addListener(listener: () -> Unit): Registration
}
```

`AsteraCommandRegistry` (pure Kotlin, `application/command`) merges same-named literals level by
level (so `admin` is contributed to by Core, Items, Match, Economy, Stats). Two executors claiming
one path, or two argument chains on one node, throw at `register` and leave the tree unchanged.
Wirings use `WiringContext.commandMount(owner)`, which records each registration in
`ctx.registrations`, so closing the context on plugin disable removes exactly what the plugin
contributed. `ICommandHandler` and `PaperCommandHandler` are deleted; all plugin.yml `commands:`
blocks are removed (Konsist: `no plugin yml declares legacy commands`,
`no command literal is a hyphenated astera root`).

### Spec DSL (vendor-neutral)

`CommandSpec.Literal` gains `permission` and `access` (`EVERYONE` | `OPERATOR`, default
`OPERATOR`: forgetting the declaration is fail-safe). `CommandSpec.Argument` gains `optional`
(trailing only). New argument types: `DoubleArg`, `PlayerName` (suggests online players),
`Choice(values)` (suggests and validates), next to `PlayerSelector`, `IntegerArg`, `StringArg`,
`ItemIdArg`. `Argument.completer` receives a `PartialInput` with the real prior arguments and the
completing `CommandActor`. Suggestion providers are plain closures over the owning plugin's
registries (item ids, stage ids, match ids, lane keys, currencies, shop ids, role ids, mob ids,
tutorial script ids, reload categories, content kinds), evaluated at completion time so late
content is reflected.

Descriptions are not stored on specs: the node at path `queue join` is described by the flat i18n
key `astera.command.help.queue-join` (`CommandPaths.descriptionKey`); a flat key because a node
can be both a leaf and a parent, which a nested YAML map cannot express.

### Permissions

Scheme `astera.<area>.<action>` on every executable leaf (`astera.queue.join`,
`astera.items.give`, `astera.match.admin.start`, `astera.admin.maintenance`, ...; existing nodes
kept so existing roles keep working). Basic player commands are `EVERYONE`; admin commands are
`OPERATOR`: the console, server operators, holders of the node through an Astera role, or through
the host permission system (`Player#hasPermission`, so LuckPerms-style nodes work).

`CommandAuthorizer` implements this and also computes **visibility**: an area is visible only if
the sender can use something below it, so hidden nodes are absent from the Brigadier tree the
client receives (`requires`), from `/astera help`, and from completion. Enforcement is central:
the same `CommandInvoker` (authorizer gate, executor, failure -> reply, exception ->
`astera.command.internal_error`) is used by the Paper bridge and by the headless runner; executors
no longer check permissions themselves. `requirePermission(permission, access)` now only declares.

To make the in-use-case permission checks (moderation, stats `show <other>`) agree,
`CorePlatformPorts.hostPermission` wraps the role table in `HostAwarePermissionService` (host
answer OR role) at priority 0. Persistence overlays keep priority 100.

### Replies

`CommandContext.reply(key, placeholders)` reaches the sender (player or console) through the
adapter, which renders the key for the sender's locale. Every executor replies on success and
returns `CommandResult.Failure(key, placeholders)` for each error. Where the GUI the command opens
(`shop`, `menu`, `vault`, `travel`) or the chat the command produces (`chat say|team|party|msg`)
is the reply, success stays silent by design.

### Paper bridge and late registration

`BrigadierTreeBuilder<S>` (generic in the Brigadier source type, testable with a real
`CommandDispatcher`) translates the merged tree: literals with `requires`, argument chains with
suggestion providers, optional arguments (every node from the last mandatory argument on is
executable), a `TokenArgumentType` for ids with `:`.

`PaperCommandBridge` publishes the tree two ways so timing never decides whether a command exists:

1. On Paper's `LifecycleEvents.COMMANDS` (server start, `/reload`) the whole current snapshot is
   registered as `astera`.
2. When the registry changes afterwards (a feature plugin enabling later or disabling), the fresh
   tree is merged into the live root node of the dispatcher (`addChild` is additive) and the command
   tree is re-sent to online players.

Brigadier cannot remove nodes, so execution and visibility **re-resolve the node by path from the
live registry** instead of closing over the spec captured at build time. A disabled plugin's
leftover nodes are inert (invisible, unrunnable) until the next `COMMANDS` event rebuilds the tree.

### Help

`/astera` and `/astera help` list the areas the sender may use; `/astera help <area>` lists every
runnable command of that area with usage (derived from the spec) and description. They walk the
live merged tree, so they cannot drift from the commands.

### Command tree

| Area | Actions (access) |
|---|---|
| `help` | `[area]` (everyone) |
| `menu`, `vault`, `news [n]`, `patch [version]`, `shop [id]`, `lang <ja\|en>`, `volume <channel> <0-100>` | everyone |
| `travel` | `pve`, `survival`, `lobby` (everyone) |
| `queue` | `join <lane>`, `leave`, `status` (everyone) |
| `match` | `list` (everyone); `start <stage>`, `end <id>`, `abort <id>`, `cancel <id>`, `restart <id>`, `phase <id> <trigger>`, `entry <id> <player>`, `replay start\|stop\|export <id>` (operator) |
| `friend` | `add`, `accept`, `remove <player>`, `list` (everyone) |
| `party` | `invite`, `kick`, `transfer <player>`, `leave`, `list` (everyone) |
| `chat` | `say`, `team`, `party <message>`, `msg <player> <message>` (everyone) |
| `tutorial` | `start <script>`, `advance`, `cancel`, `status` (everyone) |
| `report` | `<target> <reason> [comment]` (everyone) |
| `stats` | `show [player]`, `leaderboard [n]` (everyone); `xp grant <player> <amount>` (operator) |
| `economy` | `balance [currency] [player]` (everyone); `grant`, `withdraw`, `transfer` (operator) |
| `items` | `give <player> <item> [amount]` (operator) |
| `mod` | `kick`, `mute`, `unmute`, `ban`, `tempban`, `unban`, `warn`, `ratelimit`, `lift`, `history` (operator / moderator role) |
| `admin` | `maintenance`, `teleport`, `reload`, `audit`, `promote`, `demote`, `config`, `health`, `spawn-mob`, `backup`, `restart`, `announce`, `stage list\|select`, `validate`, `feedback list\|triage`, `slow-query report` (operator) |

Merged duplicates: `/astera-give` + `/astera-items give` -> `items give`; `/astera-grant-xp` +
`/astera-stats xp grant` -> `stats xp grant`; `/astera-economy currency deposit` +
`/astera-grant-currency` -> `economy grant`; the `mod` tree's `mute/kick/ban` + the flat moderation
set -> `mod kick|mute|ban|...`; the old `/astera reload` leaf -> `admin reload`; `/astera-shop browse <id>`
-> `shop <id>`. The `queue join` argument accepts a lane key of `queue.yaml` (`annihilation`) as
well as the wire form (`custom:annihilation`).

## Consequences

### Positive
- One discoverable entry point; completion and `help` are generated from the same tree.
- Operators (and LuckPerms users) can actually run admin commands; replies are localized and
  reach the console.
- Late-enabling plugins and plugin disable are handled without a restart.
- The same tree is exercised headless (`CommandTreeTest` in `:tools:game-sim`) and through a real
  Brigadier dispatcher (`BrigadierTreeBuilderTest`).

### Negative / Trade-offs
- Every old `/astera-*` command string changed (pre-release: no aliases, no shims, per the
  Phase 1 posture). Docs, GUI hints and i18n texts were updated.
- Removing nodes from a live Brigadier dispatcher is impossible, so stale nodes persist (inert)
  until the next `COMMANDS` event.
- The permission service now honours the host's answer; a deployment that wants roles-only must
  not rely on `isOp`.

### Mitigations
- `CommandMessageKeysTest` fails when a literal message key in command code is absent from en/ja.
- Konsist: every spec file declares at least as many permissions as executors.
- `CommandTreeTest` fails when a leaf has no permission, no usage or no description key.

## Alternatives Considered

- **Keep per-plugin Brigadier roots, only fix registration**: still gives `/astera-match` style
  discovery problems and a command namespace that cannot be listed centrally.
- **Rebuild the whole dispatcher on every change**: Paper exposes registration only inside the
  `COMMANDS` event; the additive `addChild` merge plus path re-resolution avoids needing it.
- **Use Bukkit `PermissionDefault` nodes registered in plugin.yml**: duplicates the access default
  per node in a second place and cannot express "or holds an Astera role".
- **Execute through Brigadier in the simulator**: would put `com.mojang` on the sim classpath; a
  small host-neutral runner sharing `CommandInvoker` keeps the sim free of Paper.

## References

- [[adr/0122-admin-operations-completeness]], [[adr/0086-admin-ops-permission-presence-feedback]],
  [[adr/0015-external-plugin-interrogation]], [[adr/0174-host-neutral-wiring-and-headless-game-simulator]]
- `plugin/core/.../application/command/` (`AsteraCommandRegistry`, `CommandAuthorizer`,
  `CommandInvoker`, `CommandTreeRunner`, `HelpCommandSpec`), `adapter/paper/command/`
  (`BrigadierTreeBuilder`, `PaperCommandBridge`)
