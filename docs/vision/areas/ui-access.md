# Area: UI / access routes

Owner track `wp/ui-access`. Seed: audit 2026-10-26. Items: Board prefix `UI-`.

## 構想 (source)
- Hotbar holds at most one menu item (compass, slot 8); the rest are the craft-grid buttons and keys F / Shift+F / Q; zero menu items in a match (ADR-0185/0211/0232).
- Play gate (arch + NPC at spawn) is the one obvious entrance; queue NPC and lane pads are secondary (ADR-0248).
- Admin tiles live in the common menu behind `visible_if: permission:` (ADR-0240). One owner per HUD channel (ADR-0177).
- PlayerSettings design: language, volume, control scheme, particle visibility, double jump, public profile (ADR-0124).
- No document lists all access surfaces or the settings set; that gap is the root of "not tidy".

## 現状
- 2026-10-27 時点 (正本は `docs/game-design/access-map.md`): 入口は 3 層に整理済み。メインメニュー 27 スロット (Play/Shop/Vault/Travel/Training/Profile/Settings/Cosmetics/Party/Friends/Achievements/Daily/Help + 管理者タイル)、NPC は 4 体 (Play/Market/Travel/Social)、試合中は `F` の 3 ボタンメニュー。
- Settings = 言語・音量 3 種・操作方式・クイックキャスト・パーティクル・二段ジャンプ・公開プロフィール。未実装: HUD 表示切替、チャット設定、通知。
- 初回ガイド (ADR-0242, `content/tutorials/getting-started.yaml`): menu → range → rack → primary (剣) → secondary → reload (銃) → staff (杖: ファイアスタッフを右クリック) → hud → norma (勝ち方を 7 秒表示) → queue。ショップ/金庫は必須経路から外した (メニューから到達可)。
- 試合中サイドバーのフッターは「自チーム / Stars / Norma Lv / 現在のゴール」。モード本体が長くてもフッターは切れない (`MatchSidebarModel`)。e2e `NormaPlaythroughE2eTest` が確認する。
- 試合中のメニュー経路は `F` のみ。コマンドはショートカット。

## 理想形
Three layers. (1) Menu (compass / F / craft grid) = the one general menu: Play, Shop, Loadout (vault), Profile+Achievements, Party+Friends, Quests+Daily, Settings, Travel+Training, Help; admin tiles in the same screen. (2) Four NPCs where place matters: Play gate (all queue entrances), Market (shop + vault), Concierge/Travel (travel, training, tutorial), Social (party, friends, leaderboard). (3) Commands are shortcuts to the same intents and open the GUI when one exists. One access map document is the source of truth.

## 未決
- Which settings ship first (HUD toggles, chat channels, notifications)? Direct language picker instead of cycling?
- In-match exit/help hint: show how to leave (`/astera queue leave`, `lobby`)?
- Does "Customize" disappear entirely (vault direct) - recommended yes.

## Governing ADRs
0124, 0177, 0185, 0211, 0232, 0240, 0242, 0248.
