---
status: Accepted
date: 2026-10-22
deciders: founder, Claude (orchestrator)
supersedes: null
superseded_by: null
---

# ADR-0232: The hotbar belongs to the player (one menu item + keybinds)

## Status

Accepted. Tightens the hotbar policy of ADR-0211 (kit in slots 5..8) and the kit of ADR-0185.

## Context

施主要望: 「ホットバーがほぼ使えない、混雑している」。ADR-0211 はロビーのメニューキットをスロット 5..8
(保管庫・ショップ・キュー・コンパス) に置き、武器を 0..4 に追いやった。武器 5 本 + メニュー 4 個でホットバーが埋まる。

## Decision

- **Lobby / training range**: メニュー用のホットバーアイテムは **最大 1 個** (コンパス "Menu", スロット 8)。
  `HotbarLayout.KIT_SLOTS = 8..8`, `MAX_KIT_ITEMS = 1`, 武器は `0..7`。`LobbyMenuKit` はロード時に 2 個目を拒否する。
- 保管庫・ショップ・キュー・訓練場・プロフィールは **メインメニュー**、インベントリの **クラフトグリッドボタン** (変更なし)、**キーバインド** から。
- **Match**: メニューアイテム 0 個 (ADR-0185 のとおりキットは試合中に外れる)。試合中は F も vanilla のまま (割り込まない)。
- キーバインドは `lobby-kit.yaml` の新 area で宣言 (`key_swap` / `key_sneak_swap` / `key_drop`, slot 0, アイテムは配らない):

| 入力 | 条件 | 動作 |
|---|---|---|
| F (持ち替え) | ロビーで武器を持っていない | メインメニュー |
| Shift+F | 同上 | 保管庫 / ロードアウト (`lobby.customize.open`) |
| Q (ドロップ) | コンパスを選択中 | キュー (`lobby.queue.show`) |

  武器のバインドは `SkillTrigger` = LEFT/RIGHT_CLICK, SNEAK_LEFT/RIGHT, HOLD, DROP(Q), SHOOT で **F は未使用**。
  衝突は無いが、武器を持っている間の F は vanilla の手持ち交換のままにする (Astera アイテム = PDC `astera:item-id` を持つスタック)。
  Q は元々ロックされたキットのドロップ抑止だったものをキューのショートカットに転用 (武器の Q は武器自体を持っているので影響なし)。
- 発見性: 参加 2 秒後にアクションバーでキー一覧を 1 回表示 (`astera.lobby-kit.hint.keys`)、コンパスの lore にキー説明 (`hint.compass`)。
- 試合中の軽量メニューは作らない (I を厚くも、I が無いと出せない O も出さないため)。

## Consequences

- ホットバー 0..7 (8 枠) が武器 / 任意アイテム用に空く。
- 古いスロット 5..7 のキット項目は `lobby-kit.yaml` から削除 (破壊的変更は Phase 1 方針により shim なし)。
- e2e (`LobbyKitE2eTest`, `MatchE2eTest`) は「キット = スロット 8 の 1 個のみ」「試合中は 0 個」「F / Shift+F でメニューが開く」「武器所持時の F では開かない」を検証する。
