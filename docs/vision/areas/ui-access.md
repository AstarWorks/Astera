# Area: UI / access routes

Owner track `wp/ui-access`. Seed: audit 2026-10-26. Items: Board prefix `UI-`.

## 構想 (source)
- Hotbar holds at most one menu item (compass, slot 8); the rest are the craft-grid buttons and keys F / Shift+F / Q; zero menu items in a match (ADR-0185/0211/0232).
- Play gate (arch + NPC at spawn) is the one obvious entrance; queue NPC and lane pads are secondary (ADR-0248).
- Admin tiles live in the common menu behind `visible_if: permission:` (ADR-0240). One owner per HUD channel (ADR-0177).
- PlayerSettings design: language, volume, control scheme, particle visibility, double jump, public profile (ADR-0124).
- No document lists all access surfaces or the settings set; that gap is the root of "not tidy".

## 現状
- 通常チャット (`plugin:chat`, ADR-0289/0299): lobby/game 間で配信。format・ランク prefix/suffix・画面 title は `&` 装飾に統一し、本文は最高優先ランクの `decoration` で許可 (admin/mod=true、member=false)。日本語変換は単語全体がかなになる場合のみ採用し、変換がなければ原文の追記なし。`/astera chat japanize on|off` で個人切替。
- 2026-10-27 時点 (正本は `docs/game-design/access-map.md`): 入口は 3 層に整理済み。メインメニュー 27 スロット (Play/Shop/Vault/Travel/Training/Profile/Settings/Cosmetics/Party/Friends/Achievements/Daily/Help + 管理者タイル)、NPC は 4 体 (Play/Market/Travel/Social)、試合中は `F` の 3 ボタンメニュー。
- Settings = 言語・音量 3 種・操作方式・クイックキャスト・パーティクル・二段ジャンプ・公開プロフィール・HUD 表示切替 3 種 (HPバー / ダメージ数字 / チュートリアルのヒント、JSONB 保存でマイグレーション不要、`HudPreferences` が 2 秒キャッシュ)。未実装: チャット設定、通知。
- 表示言語 (ADR-0283): サーバー既定は `ja` (`ASTERA_DEFAULT_LOCALE=en|ja` で変更、e2e は `en` 固定)。個人の明示選択 (`/astera lang`・Settings タイル) が最優先で、クライアントの MC 言語は見ない。`PlayerSettings.locale` は null = サーバー既定。
- メインメニューの Daily/Quests タイルは専用 GUI (`progress.open`, 54 スロット: ログインボーナス (クリックで受け取り)・ミッション一覧と進捗・クエスト現在ステージ)。`/astera daily|missions|quests` はチャットのショートカットとして残る。
- 初回ガイド (ADR-0242, UI-11, `content/tutorials/getting-started.yaml`): 「進め方」7 ステップ = menu → queue (対戦に参加) → match (対戦を最後まで) → result (直近の結果) → shop (GOLD で 1 品購入) → equip (ホットバーで手に持つ) → missions (デイリー/クエスト)。各ステップに why/how の 2〜4 行チャット説明・タイトル・ロビーサイドバーの「次のステップ」行・ホログラム補助 (プレイヤーごとの吹き出し + NPC 上の上下する矢印、完了/退出で消去)・完了時にタイトル+効果音。再接続は永続カーソルから再開。武器操作の説明は外した。ショップ/金庫は必須経路から外した (メニューから到達可)。
- 試合中サイドバーのフッターは「自チーム / Stars / Norma Lv / 現在のゴール」。モード本体が長くてもフッターは切れない (`MatchSidebarModel`)。e2e `NormaPlaythroughE2eTest` が確認する。
- 試合中のメニュー経路は `F` のみ。コマンドはショートカット。
- 殲滅戦 (UI-A3, 2026-10-29): ナビコンパス (ロビーのメニューコンパスとは別アイテム、素材 COMPASS) をホットバー slot 9 (index 8, `HotbarLayout.MATCH_COMPASS_SLOT`) に開始時と全リスポーンで配布。ドロップ・移動・死亡ロストは不可 (`PaperMatchCompass`、スナップショットには入らない)。矢印+距離のアクションバーとホログラムガイドは手に持っている間だけ表示 (`ObjectiveKit(compassItem=true)`)。柱の浮遊ラベルは常時表示。他の siege モードは従来どおり常時表示 (`compassItem` を立てれば同じ挙動にできる)。
- 武器ツールチップ (`ItemTooltip`, 唯一の組み立て箇所): 生成武器の生の `left_click: 技名` 行は「Left-click: 技名」(ja: 左クリック) のラベル付き灰色行に整形して表示する。ショップ/金庫/インベントリ共通。e2e `ShopPurchaseE2eTest` が生トリガー ID の混入を見る。

## 理想形
Three layers. (1) Menu (compass / F / craft grid) = the one general menu: Play, Shop, Loadout (vault), Profile+Achievements, Party+Friends, Quests+Daily, Settings, Travel+Training, Help; admin tiles in the same screen. (2) Four NPCs where place matters: Play gate (all queue entrances), Market (shop + vault), Concierge/Travel (travel, training, tutorial), Social (party, friends, leaderboard). (3) Commands are shortcuts to the same intents and open the GUI when one exists. One access map document is the source of truth.

## 未決
- Which settings ship first (HUD toggles, chat channels, notifications)? Direct language picker instead of cycling?
- In-match exit/help hint: show how to leave (`/astera queue leave`, `lobby`)?
- Does "Customize" disappear entirely (vault direct) - recommended yes.

## Governing ADRs
0124, 0177, 0185, 0211, 0232, 0240, 0242, 0248.
