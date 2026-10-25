# Area: UI / access routes

Owner track `wp/ui-access`. Seed: audit 2026-10-26. Items: Board prefix `UI-`.

## 構想 (source)
- Hotbar holds at most one menu item (compass, slot 8); the rest are the craft-grid buttons and keys F / Shift+F / Q; zero menu items in a match (ADR-0185/0211/0232).
- Play gate (arch + NPC at spawn) is the one obvious entrance; queue NPC and lane pads are secondary (ADR-0248).
- Admin tiles live in the common menu behind `visible_if: permission:` (ADR-0240). One owner per HUD channel (ADR-0177).
- PlayerSettings design: language, volume, control scheme, particle visibility, double jump, public profile (ADR-0124).
- No document lists all access surfaces or the settings set; that gap is the root of "not tidy".

## 現状
- 8 lobby NPCs (`content/lobby-npcs/*.yaml`): play, queue (duplicate of play), shop, gear-keeper, travel, party, profile, settings.
- GUIs in `content/gui/`: main-menu (9 slots: Play, Shop, Training, Customize, Leave queue, Travel, Profile, admin tiles). No Settings/Party/Friends/Achievements tile.
- `friends-list` and `achievement-viewer` GUIs have no entrance; `/astera achievements|friend list|daily|missions|quests` print chat only.
- Queue has 8 entrances (Play NPC, queue NPC, lane pads, menu, compass, grid result slot, Q, command).
- "Customize" opens a middle menu with 3 links (vault, profile, settings); it is not a loadout editor.
- Settings GUI = language (cycle), volume, control scheme only. `PlayerSettings` also has particleVisibility, doubleJumpEnabled, publicProfileEnabled, quickCastSlots with no UI. `lobby.hotbar.bind.<N>` has no entrance.
- Stale "Wave G / no-op" comments remain in `settings.yaml`, `SettingsGuiSpec`, `queue-npc.yaml`.
- In a match there is no menu path (by design); only commands.

## 理想形
Three layers. (1) Menu (compass / F / craft grid) = the one general menu: Play, Shop, Loadout (vault), Profile+Achievements, Party+Friends, Quests+Daily, Settings, Travel+Training, Help; admin tiles in the same screen. (2) Four NPCs where place matters: Play gate (all queue entrances), Market (shop + vault), Concierge/Travel (travel, training, tutorial), Social (party, friends, leaderboard). (3) Commands are shortcuts to the same intents and open the GUI when one exists. One access map document is the source of truth.

## 未決
- Which settings ship first (HUD toggles, chat channels, notifications)? Direct language picker instead of cycling?
- In-match exit/help hint: show how to leave (`/astera queue leave`, `lobby`)?
- Does "Customize" disappear entirely (vault direct) - recommended yes.

## Governing ADRs
0124, 0177, 0185, 0211, 0232, 0240, 0242, 0248.
