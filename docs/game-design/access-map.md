# アクセスマップ (UI / 導線の正本)

プレイヤーが機能に辿り着く道を一枚で固定する。**機能を足すときはまずこの表に 1 行足す。** 入口が表にない機能は作らない。
コードと食い違ったらコードが勝つ (`content/gui/`, `content/lobby-npcs/`, `content/gui/kit/lobby-kit.yaml`, コマンド木)。
関連: ADR-0232 (ホットバーはプレイヤーのもの), ADR-0248 (Play gate), ADR-0240 (管理者タイル), ADR-0124 (PlayerSettings)。

## 原則

入口は 3 層。それ以上は作らない。

1. **Menu** — コンパス (スロット8) / `F` / クラフトグリッドの「メニュー」ボタン = 唯一の総合メニュー (`content/gui/main-menu.yaml`)。全機能のタイルがここにある。
2. **NPC** — 場所性が要る 4 体の**役割ハブ**だけ: Play / Market / Travel / Social。
3. **コマンド** — 同じ intent を呼ぶ**ショートカット**。GUI を持つ機能のコマンドは必ずその GUI を開く。

禁止: 同じ GUI を開く NPC の重複、中継だけのメニュー (旧「Customize」)、コマンドを知らないと届かない機能。
試合中のメニューは `F` で開く 3 ボタンの小メニュー 1 つだけ (下表「試合中メニュー」)。それ以外の入口はコマンド。

## 機能 → 正規の入口

| 機能 | 正規の入口 (これが正) | ショートカット | 備考 |
|---|---|---|---|
| Play (モード選択 / vs bots) | NPC **Play gate** (スポーン正面) | Menu「Play」/ `Q`(コンパス選択中) / グリッド結果枠 / `/play <lane>` / `/astera queue join` | ADR-0248。南島のレーンパッドは「歩いて入る」副次入口として残す |
| キュー退出 | コンパス (キュー中は「Leave queue」に差し替わる) | Menu「Leave queue」(キュー中のみ) / `/astera queue leave` | |
| Shop | NPC **Market** | Menu「Shop」/ グリッド / `/astera shop` | |
| 個人金庫 (Vault) | Menu「Vault」 | `Shift+F` / `/astera vault` | 中継の Customize 画面は廃止。直接開く |
| Travel (PvE / Survival / Lobby) | NPC **Travel** | Menu「Travel」/ `/astera travel` | |
| Training (訓練島) | NPC **Travel** の画面内「Training」 | Menu「Training」 | 橋でも歩いて行ける |
| Profile (統計カード) | Menu「Profile」 | グリッド / `/astera stats show` | 専用 NPC は廃止 |
| Party | NPC **Social** (Social ハブ画面内) | Menu「Party」 / `/astera party ...` | |
| Friends | NPC **Social** (Social ハブ画面内) | Menu「Friends」 / `/astera friend ...` | |
| Achievements | NPC **Social** (Social ハブ画面内) | Menu「Achievements」 / `/astera achievements` (チャット) | GUI 版コマンド化は stats 側の依頼 |
| Daily / Missions / Quests | Menu「Daily & Quests」 | `/astera daily` `/astera missions` `/astera quests` | GUI 未実装。タイルはコマンドの案内をチャットに出す。GUI 化したらタイルの intent を `gui.open` に差し替える |
| Settings | Menu「Settings」 | グリッド / `/astera settings` | 専用 NPC は廃止 |
| Help / チュートリアル | Menu「Help」 | `/astera help` / `/astera tutorial start\|skip\|status` | |
| Social ハブ画面 | NPC **Social** | `/astera social` | Party / Friends / Achievements の 3 タイル |
| 管理者パネル / アイテムカタログ | Menu の管理者タイル (`visible_if: permission:astera.admin.panel`) | グリッド (管理者のみ差し替え) / `/astera admin panel` | ADR-0240 |
| リーダーボード | ロビーのボード (`content/lobby-boards/`) | `/astera stats leaderboard` | 見るだけの掲示 |

## NPC (4 体)

| NPC | id | 場所 | 右クリック |
|---|---|---|---|
| Play | `play-npc` | スポーン正面アーチ (0.5, 4.5) | `lobby.queue.show` (モード選択) |
| Market | `market-npc` | 東の市場・ショップ屋台 | `lobby.shop.open` |
| Travel | `travel-npc` | 広場 (-3.5, -3.5) | `lobby.travel.show` (PvE / Survival / Training / Lobby) |
| Social | `social-npc` | 広場 (4.5, -3.5) | `gui.open {gui: social-hub}` |

廃止: `queue-npc` (Play と重複) / `profile-npc` / `settings-npc` (Menu へ) / `gear-keeper-npc` (中継 Customize のため) / `party-npc` (Social へ統合)。
金庫カウンター (東の市場の 2 つ目の屋台) は景観として残る。

## メインメニュー (`content/gui/main-menu.yaml`, 27 スロット)

| 行 | スロット | タイル → 行き先 |
|---|---|---|
| 0 | 0 / 4 / 8 | Admin hub (管理者) / Leave queue (キュー中のみ) / Item catalog (管理者) |
| 1 | 10..17 | Play → `lobby.queue.show` / Shop → `lobby.shop.open` / Vault → `lobby.vault.open` / Travel → `lobby.travel.show` / Training → `lobby.training.travel` / Profile → `lobby.profile.show` / Settings → `gui.open settings` / Cosmetics (見た目) → `cosmetic.open` |
| 2 | 19..23 | Party → `lobby.party.show` / Friends → `lobby.friends.show` / Achievements → `lobby.achievement.show` / Daily & Quests → `lobby.hint.show progress` / Help → `lobby.hint.show help` |

## キー / ホットバー (`content/gui/kit/lobby-kit.yaml`)

| 入力 | 動作 |
|---|---|
| コンパス (スロット8) | メインメニュー。キュー中は「Leave queue」 |
| `F` (武器を持っていない時) | メインメニュー (ロビー)。試合中は `F` が試合メニュー (下記) |
| `Shift+F` (武器を持っていない時) | 個人金庫 (管理者もここは金庫。管理パネルは Menu のタイル) |
| `Q` (コンパス選択中) | Play のモード選択 |
| クラフトグリッド | メニュー / プロフィール / ショップ / 設定 (管理者はショップ・設定がアイテムカタログ・管理パネルに差し替わる) と結果枠 = Play。ADR-0185 の決定のまま残してある (Menu のショートカット) |

## 設定画面 (`content/gui/settings.yaml`, `PlayerSettings`)

1 画面にすべて。タイルは設定を保存してチャットで返し、トグルは画面を開き直す。

| 設定 | タイル | 保存先 | コマンド |
|---|---|---|---|
| 言語 | 循環 (en ↔ ja) | `locale` | `/astera lang <code>` |
| 音量: 音楽 / 効果音 / ボイス | 100→75→50→25→0 の循環 | `musicVolume` / `soundVolume` / `voiceVolume` | `/astera volume <ch> <0-100>` |
| 操作方式 | RightClick ↔ Button | `controlScheme` | — |
| クイックキャスト割当 | 「Quick-cast slots」→ スロット 1..9 の選択画面 → スキル選択 | `quickCastSlots` | — |
| パーティクル表示 | 全員 → 自チーム → 自分のみ | `particleVisibility` | — |
| 二段ジャンプ | ON / OFF | `doubleJumpEnabled` | — |
| 公開プロフィール | ON / OFF (既定 OFF = プライバシー) | `publicProfileEnabled` | — |

未実装 (モデルに無い): HUD 表示切替 (サイドバー / ネームプレート / ダメージ数字)、チャット設定 (チャンネル / ミュート)、通知。
追加するときは `PlayerSettings` にフィールドを足し、この表と `settings.yaml` に 1 行ずつ足す。

## 試合中メニュー (`content/gui/match-menu.yaml`, 9 スロット, UI-06)

試合中はホットバーにメニューアイテムを持たない (ADR-0232)。代わりに **`F` (手の入れ替え) で小メニューを開く**。
試合中だけバニラの入れ替えをキャンセルする (武器を持っていても同じ)。ロビーの `F` = メインメニューは変わらない。
試合に入った時にチャットで「F: 試合メニュー」を 1 行案内する。

| スロット | ボタン | 動作 |
|---|---|---|
| 2 | Settings | `gui.open settings` (設定画面) |
| 4 | Leave | 確認画面 (`match-menu-confirm`) → 確定で既存の離脱フロー (`ReturnToLobbyUseCase`: `PlayerLeavingForLobby` → スター放出・離脱ルール)。ロジックは複製しない |
| 6 | Help | チャットにノルマ / スター / 武器制限の説明 |

コマンド (`/astera queue leave` / `/astera travel lobby` / `/astera settings` / `/astera help`) は引き続きショートカット。

## 変更の手順

1. この表に行を足す (正規の入口を 1 つだけ決める)。
2. 入口が GUI なら `content/gui/<id>.yaml`、NPC なら `content/lobby-npcs/<id>.yaml`、キーならロビーキットを編集。
3. コマンドを足すときは GUI を開く (`buildOpenGuiCommandSpec`) か、GUI が無いことを備考に書く。
4. `LobbyAccessMapScenarioTest` (game-sim) / `LobbyAccessMapE2eTest` (e2e) が NPC 数・メニュータイル・設定の保存を見ている。
