---
status: Accepted
date: 2026-10-22
deciders: founder + Claude
supersedes: null
superseded_by: null
---

# ADR-0233: Admin panel command console (a GUI path for every operator command)

## Status

Accepted

## Context

ADR-0183 gave the admin panel a hub with hand-written pages (item catalog, match control, players, server,
self tools, skills). The founder's request after ADR-0184 turned every admin command into a node of the single
`/astera` tree: "typing commands is tedious, the admin GUI should cover management". About 50 operator
commands (moderation, economy, XP, items, match control and replay, content reload/validate/config/stages,
maintenance, announce, restart, drain, backup, health, audit, feedback, slow queries, resource pack, roles,
spawn-mob, skills) had no GUI path. One bespoke page per command would drift from the command syntax (argument
names, bounds, suggestion providers) and from the permission nodes the moment a command changes.

Constraints: ADR-0022 (core cannot import feature plugins), ADR-0021/0096/0114 (declarative GUI, intents are
values, no lambdas in specs), ADR-0184 (one tree, one `CommandInvoker`, central authorization), ADR-0183 (the
guard re-checks the permission on every click).

## Decision

1. **The form is derived from the live command node.** `AdminCommandConsole` (core) reads the merged tree
   (`IAsteraCommandRegistry.snapshot()`, resolved at click time) and builds, per command, a form with one row
   per argument: name, required/optional, current value, picker. Argument types decide the picker: online-player
   heads (`PlayerSelector`, `PlayerName`), buttons (`Choice`), and for everything else preset buttons, recently
   used values and the argument's own `completer` (item ids, stage ids, match ids, lane keys, reload categories,
   role ids, ...), paged 36 per page, with a search filter. Free text and numbers are typed in chat through
   `IAdminTextPrompt` (the GUI closes, the next chat line is swallowed and validated against the argument type,
   `cancel` aborts).
2. **Running goes through the same `CommandInvoker` as typing the command.** The per-node permission gate, the
   executor, the localized replies and the internal-error mapping are identical. Replies are captured and
   shown on a paged **result page** (success/failure icon, one row per line) and echoed to chat, so
   `validate`, `health`, `audit`, `feedback list`, `slow-query report`, `stage list` need no dedicated result
   screens. Destructive actions (kick, mute, ban, tempban, withdraw, match end/abort/cancel/restart/phase,
   maintenance, restart, drain, backup, config, promote/demote, lobby repaste) show a confirm page first.
3. **`AdminCommandCatalog` declares the GUI path of each command**, as data: category (moderation, economy,
   items, match, content, server, support, players and roles, skills), `destructive`, preset buttons per
   argument (reason presets sent in the operator's language, durations, amounts, announcement texts,
   drain targets `lobby`/`game`) and `playerArg`, the argument that a player's page pre-fills. Entries are
   registered into `IAdminPanelRegistry.registerAction`, so feature plugins can add their own.
4. **Parity is a test, not a promise.** `AdminConsoleScenarioTest` (game-sim) walks the live tree of an
   `ALL`, a `LOBBY` and a `GAME` backend and fails for every `OPERATOR` leaf without a registered action. The
   only allowlist entry is `admin panel` (it opens the panel). It also fails for stale actions, presets that name
   unknown arguments, and opens the form of every action (at most nine argument rows).
5. **Two GUI-only actions have no command behind them** (path starts with `@`): `@send-player` moves one
   online player to `lobby` or `game` through `IServerTransferGateway` (the proxy messenger on a split
   deployment, so it works from either backend), and `@lobby-repaste` rebuilds the lobby world in place
   (`ILobbyRepaster`, Paper-only, clears the build and pastes the plan again; only offered on lobby-hosting
   backends).
6. **Role awareness.** An action is offered only when its node exists in this backend's tree and the viewer
   holds its permission (`CommandAuthorizer.canUse`), so the lobby and the game backend each show exactly
   what they can run. The panel on a lobby backend reaches game-side operations by sending the operator over
   (`@send-player` on themselves) or via `admin drain`; commands are not forwarded between backends.
7. **Navigation.** Hub tile "Commands" -> categories -> actions -> form -> picker/confirm -> result, with a
   back button to the previous level and the hub on every page, pagers, and a per-player tile ("Moderation and
   commands") on the player page that pre-fills the target. Every click is guarded (`AdminPanelGuard`,
   permission re-checked) and every run writes `AuditEvent.AdminActionPerformed(action = "cmd:<path>")`.
8. **Quick access without a hotbar item.** `PaperAdminQuickAccessListener`: sneak + swap-hands (F) opens the
   hub for operators and holders of `astera.admin.panel`; a menu-kit item in either hand keeps its own
   handling, so the lobby kit (ADR-0185) is untouched.

## Consequences

### Positive
- A new operator command costs one catalog line; its form, argument validation, suggestions, permission and
  replies come from the command itself. CI fails until the line exists.
- GUI and typed commands cannot diverge in behaviour: one invoker, one authorizer.
- Results of read-style commands are visible without chat scrolling.

### Negative / Trade-offs
- Free text still goes through chat (a chest GUI has no text field; anvil/sign input would need a Paper-only
  adapter and the chat prompt is portable and testable headlessly).
- The form is generic: no bespoke previews (for example no item icon next to the item id).
- `PlayerSelector` arguments accept online players only, as the command does; offline names use the typed path
  of `PlayerName` commands.
- Preset values for reasons are localized text stored in the moderation history in the operator's language.
- Recent values are kept in memory per restart, not persisted.
- The simulator has no `IAdminPlayerGateway` that mirrors its players, so scenarios `join` them into the
  in-memory gateway before using player pickers.

## References

- [[adr/0183-admin-gui-panel]], [[adr/0184-single-astera-command-tree]], [[adr/0185-lobby-menu-kit]],
  [[adr/0180-lobby-world]]
- `plugin/core/.../application/admin/panel/` (`AdminCommandConsole`, `AdminCommandCatalog`,
  `AdminPanelAction`), `adapter/paper/admin/` (`PaperAdminTextPrompt`, `PaperAdminQuickAccessListener`),
  `tools/game-sim/.../AdminConsoleScenarioTest.kt`
