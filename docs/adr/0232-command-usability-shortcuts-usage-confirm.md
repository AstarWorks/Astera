---
status: Accepted
date: 2026-10-22
deciders: founder + Claude
supersedes: null
superseded_by: null
---

# ADR-0232: Command usability: shortcuts as redirects, usage blocks, tooltips, confirm

## Status

Accepted. Addendum to [[adr/0184-single-astera-command-tree]]; the single `/astera` tree, the
`IAsteraCommandRegistry` and the permission model are unchanged.

## Context

After ADR-0184 the commands work, but the founder judged them hard to use: a bare `/astera` only
printed help, the common player actions needed the full `/astera queue join <lane>` spelling, a
missing argument produced Brigadier's generic "Unknown or incomplete command", completion showed
bare lane names with no hint of how full they are, replies for the queue and the match list had no
clickable follow-ups, and destructive admin commands ran on first keystroke.

## Decision

1. **Bare `/astera`** opens the main menu for a player (falling back to the help overview when the
   menu cannot open and for the console). `/astera help [area]` is unchanged.
2. **Shortcuts are redirects, not specs.** `CommandShortcuts.TABLE` is the only mapping:
   `/play` -> `queue join`, `/menu`, `/vault`, `/shop`, `/lang` -> the same-named areas.
   `PaperCommandBridge` registers each as a Brigadier literal that `redirect`s to the node of the
   built `/astera` tree (same `requires`, same command), so permission, completion and executor stay
   in ONE place; a shortcut whose target is not mounted (owner plugin absent) is skipped. The
   headless `CommandTreeRunner` expands the same table, so game-sim exercises it. Nothing is added
   to the registry, so the Konsist "no hyphenated root" and "plugins never register a root" rules
   hold. The set is deliberately small (the five most used player actions); admin areas get none.
3. **Usage instead of a generic error.** Every Brigadier node of the skeleton is executable and
   `run` prints the usage block whenever a mandatory argument is missing or the node only groups
   sub-commands. `CommandUsage.replies` builds the block: syntax (`astera.command.usage`),
   description (the existing `astera.command.help.<path>` key), the visible sub-commands
   (`astera.command.usage-sub`) and an optional copy-paste example
   (`astera.command.example.<path>`, shown only when the language files define it). A typo in a
   literal or a value of the wrong type is still rejected by Brigadier before our code runs (the
   vanilla message); that cannot be intercepted without a catch-all greedy node that would pollute
   completion, so it is deliberately not done.
4. **Suggestion tooltips.** `CommandSpec.Argument.tooltips: (PartialInput) -> Map<suggestion, text>`
   attaches hover text to individual suggestions (Brigadier `Suggestion#getTooltip`; vanilla clients
   render it). First user: `queue join <lane>` shows `waiting/needed` per lane (e.g. `2/4`).
5. **Confirm for destructive commands.** `CommandConfirm` adds a trailing optional `confirm`
   argument (`--confirm`) and a `guard`: a player without the flag gets the clickable prompt
   `astera.command.confirm.required` that re-runs the command with the flag. The console and RCON
   (scripts, e2e teardown) are never asked. Applied to `match abort` and `match cancel`
   (`match end` is the normal, rewarded way to finish and stays unconfirmed).
6. **Clickable replies** are MiniMessage in the language files (no new Kotlin text): `queue status`
   appends `[Leave queue]`; `match list` uses `match-list-line-op` for operators with `[End]` and
   `[Abort]` (the latter suggests the confirm form).

## Consequences

- Positive: players reach the common actions in one short word; wrong input explains itself in the
  player's language; the tree remains the single source of truth (tests assert the redirect expands
  to the same node).
- Negative: every skeleton node is now executable, so the client can no longer use "not executable"
  to hint incomplete input (cosmetic only). Descriptions for per-locale client tooltips of the
  *commands themselves* (not suggestions) are not provided: Paper's `Commands#register` takes one
  plain description string, not a per-locale component.

## Alternatives Considered

- **Register `/play` etc. as extra specs in the registry**: duplicates permission/completion.
- **Click-to-confirm via a pending-confirmation token**: more state for no gain over a flag the
  player (or a click) supplies.
