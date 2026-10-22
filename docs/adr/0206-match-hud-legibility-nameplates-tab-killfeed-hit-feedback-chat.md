---
status: Accepted
date: 2026-10-22
deciders: founder + Claude (HUD owner)
supersedes: null
superseded_by: null
---

# ADR-0206: Match HUD legibility - team nameplates, tab list, kill feed, hit feedback, chat

## Status

Accepted. Extends ADR-0177 (one owner per HUD channel) with five more channels that
had no owner at all; replaces the tab-list roster push of ADR-0138 and the anonymous
kill-feed toasts of ADR-0117 / ADR-0118. ADR-0182 (status line) and ADR-0194 (no boss
bars) are unchanged.

## Context

Founder: "HUD still has many problems". An audit of what a client receives (see the
table in the PR / final report) found that after ADR-0177 / 0182 / 0194 / 0197 the
status line and sidebars were sound, but the rest of the screen was vanilla or wrong:

- Nobody could tell teams apart: nameplates, tab list and chat were all white.
- The tab list had no header or footer, and a "roster push" (ADR-0138) rewrote every row
  with a plain-text display name and ping 0, which would also have erased any team colour.
- The kill feed said "A player was eliminated." (placeholders defined, never filled),
  the killer's toast "You eliminated an opponent.", and the victim got a second,
  anonymous toast next to the death screen.
- A hit gave the attacker no confirmation beyond the victim's HP bar.
- Chat was `<Name> message` for everyone.

## Decision

1. **Name styling is a port** - `IPlayerLabelGateway.apply(viewer, labels)` /
   `clear(viewer)` with `PlayerLabel(group, colorName, hp)`. The Paper adapter writes to
   the **viewer's own** scoreboard (every viewer already has a private one for the
   sidebar): one team per group (`ast_np_<group>`, colour = team colour, which colours
   the nameplate and the tab row and makes vanilla sort the tab list by team) and a
   `BELOW_NAME` dummy objective `ast_np_hp` whose scores are the exact HP ("134 heart").
   A viewer still on the shared main scoreboard is skipped (styling it would restyle
   everyone). Idempotent: last applied state per viewer, board identity checked.
2. **`MatchNameplateService`** (match) is the only producer: every participant of a live
   match sees every participant, styled by team colour (`astera.match.team.<id>.color`)
   with whole HP (rounded up, a living player never reads 0). Triggers: match start,
   phase change, health change, match end, leaving; plus a 1 Hz poll that repairs a
   swapped scoreboard. Lobby: no styling.
3. **Tab list header / footer** are owned by `TabHeaderFooterService` (match): brand +
   "Lobby" + online count, or brand + mode title + own team + players in the match.
   `PaperTabList` now parses MiniMessage (it printed tags literally). The ADR-0138 roster
   push (`UpdateTabListOnPlayerJoinedListener`) is deleted.
4. **Kill feed** (`KillfeedOnPlayerKilledListener`): one chat line per match member,
   in their language - `[Kill] <killer> defeated <victim> with <weapon>` - names in team
   colours; killerless deaths read `<victim> died`. The killer also gets a subtitle
   naming the victim. The victim's screen belongs to the death coordinator (F3-5), so the
   anonymous victim toast is gone. The weapon label is shared with the death screen
   (`KillWeaponLabel`). Chat, not the action bar: the action bar is the toast channel.
5. **Hit feedback** (`HitFeedbackListener`, combat): on `DamageDealt` with a player
   attacker, `IDamageIndicatorGateway` shows a floating number (white, gold bold for a
   critical hit, grey 0 for a blocked one) **to the attacker only** and the attacker hears
   a hit-marker sound (different for critical, blocked and the killing blow). Paper: a
   short-lived, non-persistent `TextDisplay`, hidden by default and shown to the attacker
   with `Player.showEntity`, rising by client-side interpolation, removed after 20 ticks.
6. **Chat** is rendered `Name >> message` (`PaperChatFormatListener`), the name in the
   sender's team colour inside a match, grey in the lobby. Only the renderer changes.

Texts are i18n keys (`astera.killfeed.*`, `astera.hud.tab.*`), en and ja in parity.

## Consequences

- Team identity is visible on three surfaces that agree (nameplate, tab, chat/kill feed),
  through one colour definition (`TeamColors`).
- HP under the nameplate is shown to **all** participants including enemies: simple and
  readable; a different rule (allies only) is a different map per viewer, no new port.
- The three new adapters are Paper-only and not exercised by unit tests; the
  `HudE2eTest` protocol-bot checks cover team packets, the BELOW_NAME objective, tab
  header/footer, text-display spawns per client and sounds.
- Not done here (see the final report): status-effect icons, a hit-marker glyph on the
  crosshair, per-viewer "enemy HP only when low" rules, team chat channel, objective
  markers.
