---
status: Accepted
date: 2026-05-20
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0096: Dialog domain + Kotlin DSL for GuiSpec + DialogSpec

## Status

Accepted

## Context

User audit (2026-05-20) で GUI domain の expressive 力に 2 つの gap 発見:

1. **「Kotlin DSL とかで自由に構築したい」** — 既存 `GuiSpec.build {}`
   builder は最小限 (`slot(SlotIndex, GuiSlot)` のみ)、Border range / repeat
   grid / per-slot block style 等の DSL primitives なし。動的 GUI 生成
   (BedWars 風 shop で 100+ item を grid layout、admin が item list から
   動的 generate 等) は spec を手動構築するか YAML 経由必須。
2. **「新しくダイアログも出たのでそれも使えるようにしたい」** — Minecraft
   1.21.6+ で Paper Dialog API (`io.papermc.paper.dialog.Dialog`,
   `DialogBase`, `TextDialogInput`/`NumberRangeDialogInput`/`BooleanDialogInput`/
   `SingleOptionDialogInput`, `DialogActionCallback` server callback) が
   追加されたが、Astera domain に対応 port なし。Inventory GUI と本質的に
   異なる **promise-like 1-shot interaction** type — form input + buttons
   が native render、`Player.showDialog()` で adapter が server-driven 表示。

Pre-Wave-6 readiness: Wave 6b で Paper adapter 書く時に「Dialog 対応 port
無い」「DSL primitives 無い」となり scope 膨張するリスク。Wave 5i で
domain commitment 完了。

## Decision

### §1. Dialog domain (vendor-neutral Paper Dialog API mirror)

新 domain (in `plugin/core/.../domain/dialog/`):

- `DialogId` value class (UUID-backed)
- `DialogSpec(id, titleKey: MessageKey, body: List<DialogBody>, inputs: List<DialogInput>, buttons: List<DialogButton>, afterAction)`
- `DialogBody` sealed:
  - `PlainText(messageKey: MessageKey)` — MiniMessage render
  - `ItemPreview(itemId: ItemId)` — icon + tooltip (Wave 6b adapter は IItemRegistry 経由解決)
- `DialogInput` sealed 4 variant (Paper API 1:1):
  - `TextField(key, labelKey, initial, maxLength: 1..256, multiline: Boolean)`
  - `NumberField(key, labelKey, min, max, initial in min..max, step > 0)` (Paper の Float granularity)
  - `BooleanField(key, labelKey, initial)`
  - `SingleSelect(key, labelKey, options: non-empty List<SelectOption>, initialKey?: must match option)` + inner `SelectOption(key, labelKey)`
- `DialogButton(labelKey, tooltipKey?, widthPx in 1..400, intent: GuiIntent)`
  — intent は GUI と同 `IGuiIntentRouter` 経由 dispatch、payload に input
  values が adapter で merge される
- `DialogAfterAction` enum: `Nothing` (multi-step wizard) / `CloseDialog`
  (default、典型 confirmation) / `WaitForResponse` (long-running 系)
- `DialogResponse(playerId, dialogId, triggeredButtonIntent, inputValues: Map<String, String>)`
  — stringified (Boolean → "true"/"false", Number → "%.2f" via Locale.ROOT,
  SingleSelect → SelectOption.key)
- 3 domain event: `DialogShown` / `DialogResponseReceived` / `DialogClosed`
- `DialogCloseReason` sealed: `ButtonClick(intent)` / `ForceClose` / `PlayerQuit`

新 port: `IDialogGateway.showDialog(playerId, spec, onResponse?) /
closeDialog(playerId)` + `DialogError` sealed (`UnknownPlayer / InvalidSpec / Storage`)。
`onResponse` callback は in-process direct continuation 用、event bus は
cross-plugin / audit 用 (棲み分け明示)。

Wave 5i: `InMemoryDialogGateway` (priority 0、no-op default + `DialogShown` /
`DialogClosed` event 発火のみ) + `FakeDialogGateway` (test-fixtures、
`simulateResponse(playerId, dialogId, buttonIndex, inputs)` test helper)。
**Wave 6b で `PaperDialogGateway`** が `Player.showDialog()` + 
`DialogActionCallback` を実体化。

### §2. Kotlin DSL for GuiSpec + DialogSpec

#### GuiSpec DSL 拡張

新 file: `core.application.gui.GuiSpecBuilder.kt` (DSL primitives は
application 層配置 — Konsist `gui slot variants must not contain lambda
fields` rule で domain は lambda 禁止のため、builder pattern は application
層に置く)。

`@GuiSlotDsl` marker + extension functions:

```kotlin
val shopGui = GuiSpec.build(shopId, titleKey, CHEST_27) {
    border(rows = 0..0, "gui-pane-dark")
    border(rows = 2..2, "gui-pane-dark")

    repeatGrid(items = offers, startIndex = 10, columns = 7) { offer, _ ->
        item(offer.itemId)
        onLeftClick("shop.purchase") {
            "offer-id"(offer.id.value)
            "qty"("1")
        }
        onRightClick("shop.preview") { "offer-id"(offer.id.value) }
        tooltip("astera.shop.tooltip.left-buy", "astera.shop.tooltip.right-preview")
    }

    reactive(slot = 22, binding = "player.coins.gold") {
        tooltip("astera.shop.balance.gold")
    }

    static(index = 26) {
        item("gui-pane-red")
        onLeftClick("gui.close")
        tooltip("astera.gui.common.close.tooltip")
    }
}
```

primitives:
- `border(rows: IntRange, itemId)` / `borderColumn(columns: IntRange, itemId)` / `borderFrame(itemId)`
- `repeatGrid<T>(items, startIndex, columns, block)` — 9-wide chest grid 計算
- `static{} / reactive{} / confirmation{} / textInput{} / empty(index)`
- `PayloadBuilder` with `"key"("value")` operator shorthand (Gradle style)

#### DialogSpec DSL

新 file: `core.application.gui.DialogSpecBuilder.kt` (`@DialogDsl` marker):

```kotlin
val confirm = dialogSpec("astera.shop.confirm.title") {
    body {
        text("astera.shop.confirm.prompt")
        itemPreview("iron-sword")
    }
    inputs {
        number("quantity", "astera.shop.confirm.quantity-label", min = 1f, max = maxStock.toFloat()) {
            initial(1f)
            step(1f)
        }
        bool("save-for-recent", "astera.shop.confirm.save-recent", initial = true)
    }
    buttons {
        button("astera.shop.confirm.buy-button") {
            tooltip("astera.shop.confirm.buy-tooltip")
            intent("shop.purchase.confirm") {
                "offer-id"(offer.id.value)
            }
        }
        button("astera.shop.confirm.cancel-button") {
            intent("gui.close")
        }
    }
    afterAction(DialogAfterAction.CloseDialog)
}
```

builders: `DialogSpecBuilder / DialogBodyBuilder / DialogInputsBuilder /
DialogButtonsBuilder / DialogButtonBuilder` + field builders
`TextFieldBuilder / NumberFieldBuilder / SingleSelectBuilder`。

2 example dialog 同梱: `ShopPurchaseConfirmDialog.kt` (quantity slider +
save-recent toggle + buy/cancel)、`PlayerSettingsDialog.kt` (display name +
language select + particles toggle + hud-scale slider + save/cancel)。

### §3. Inventory GUI と Dialog の use-case 区別

| 観点 | Inventory GUI (`IGuiAdapter`) | Dialog (`IDialogGateway`) |
|---|---|---|
| Lifetime | Persistent (player が close するまで開く) | 1-shot (button click で auto-close、`afterAction` で制御) |
| Reactive binding | ✅ `Reactive` slot + `GuiStore` で auto re-render | ❌ static render only |
| Form input | 🟨 `Confirmation` slot (2-button) + `TextInput` slot (anvil GUI Wave 6b) | ✅ native Text/Number/Boolean/Select inputs |
| 階層 (sub-menu) | manual page-flip | 不向き (1-shot) |
| 用途 | shop / vault / loadout selector | confirmation / settings form / match join 確認 / admin parameter prompt |

両者 coexist。Wave 5h の `GuiSlot.Confirmation` / `TextInput` slot は
Inventory GUI 内 confirmation/input が必要な context (shop GUI の中で
直接購入確認等) で引き続き使用。独立した modal (settings form) は Dialog
を使う方が UX 優れる。

### §4. `Confirmation` / `TextInput` slot の Phase 4 deprecation 検討

Wave 5h で `GuiSlot.Confirmation` + `GuiSlot.TextInput` が land したが、
Wave 5i で Dialog が land した結果、機能重複あり:

- `GuiSlot.Confirmation` ↔ Dialog の 2-button + body
- `GuiSlot.TextInput` ↔ Dialog の TextField input

Inventory GUI 内 inline confirmation/input は引き続き有効 (shop の特定
slot に小さい確認 prompt 等)、独立 modal は Dialog 推奨。**Phase 4 で
usage telemetry を見て、いずれかが unused なら deprecation 検討**。
本 ADR では coexist 維持。

### §5. Konsist enforcement (+1 rule, baseline 32 → 33)

新 rule: **`Dialog domain must not import Paper Dialog API (ADR-0096)`** —
`core.domain.dialog.*` の全 import を scan、`org.bukkit.* / io.papermc.* /
org.spigotmc.* / com.destroystokyo.paper.* / com.mojang.*` prefix 禁止。
`org.joml.*` は許容 (Wave 5h-fix principle、pure-math は domain OK)。

## Consequences

### Positive

- **Wave 6b の Paper adapter は zero-design impl**: Paper Dialog API →
  Astera DialogSpec の type 変換 + `DialogActionCallback` wiring だけ書けば
  Dialog 機能完成
- **DSL で BedWars 風 shop が宣言的に書ける**: 動的 item list を 5 行
  Kotlin で grid layout、`onLeftClick / onRightClick / tooltip` で多態的
  click 振る舞い、payload は `"key"(value)` operator で読みやすい
- **Dialog の form input は YAML より型安全**: Number range / Boolean
  initial / SingleSelect options 検証が init で fail-fast、YAML だと
  parse-time に検出
- **Inventory GUI と Dialog の棲み分け明確**: shop = persistent (Inventory)、
  confirmation = 1-shot (Dialog)、settings form = native overlay (Dialog)
- **Adventure / Paper dependency が edge 1 ヶ所に閉じる**: 既存
  `IGuiAdapter` adapter と同様、`PaperDialogGateway` だけが Paper API を
  import、domain は vendor-neutral

### Negative / Trade-offs

- **DSL は domain 層に置けない**: `Function*` 型を抱える builder が
  Konsist `gui slot variants must not contain lambda fields` rule に
  抵触するため、`core.application.gui.*` 配置。domain にも load しても
  問題ないが builder-only file が application に住むのは一見不自然 (KDoc で
  rationale 明示)
- **`GuiSlot.Confirmation/TextInput` と Dialog の機能重複**: Wave 5h 直後
  に landing したばかりの slot variants が Dialog で代替可能になった。
  Phase 4 で usage telemetry を見て deprecation 検討 — ADR §4 で明示
- **Paper Dialog API は MC 1.21.6+ で安定間もない**: Wave 6b 着手時に
  Paper API 変更があれば adapter のみ更新で対応、domain は vendor-neutral
  なので影響 small。Paper Javadoc の `Player#showDialog()` signature は
  release-by-release で changing 可能性
- **DSL の表現力 vs YAML 重複**: YAML は declarative content authoring
  (designer 編集)、DSL は dynamic generation 用 (動的 shop / admin tools)。
  両者は補完関係 — usage 棲み分けを ADR §2 で明示

### Mitigations

- Konsist +1 rule で Dialog domain Bukkit-free を mechanical 監視
- DSL builder が `core.application.gui.*` 配置の理由は KDoc + ADR §2 で文書化
- Phase 4 で `GuiSlot.Confirmation/TextInput` deprecation 検討は別 ADR で

## Alternatives Considered

- **Dialog を `IGuiAdapter` に method 追加** (`adapter.showDialog(...)`):
  却下、Dialog = "GUI の特殊 rendering" ではなく **異なる interaction
  paradigm**。port 分離が clean
- **Dialog domain を Paper API 直接 wrapping** (vendor-neutrality 妥協):
  却下、ADR-0002 / ADR-0072 の原則と一貫しない。Wave 6b adapter 変換
  cost は許容範囲
- **DSL を domain layer 配置**: 却下、Konsist rule `gui slot variants
  must not contain lambda fields` に抵触。builder = lambda 持つ → application
  層配置
- **YAML config for Dialog** (Inventory GUI と同じ approach): 却下、Wave 5i
  時点では Kotlin DSL only (gameplay 用途は動的構築が大半、designer 編集
  目的の static dialog は稀)。Phase 4 で Web admin 編集対応 → YAML 化
  検討
- **`Confirmation`/`TextInput` slot を Wave 5i で削除** (Dialog で代替):
  却下、Inventory GUI 内 inline confirmation/input の use case あり、
  coexist 維持。Phase 4 で usage telemetry を見て判断
- **`DialogButton.intent` ではなく callback lambda**: 却下、ADR-0021
  silly-knuth §5 の「intents are pure values」原則と一貫。callback は
  `IDialogGateway.showDialog(... onResponse = { ... })` の 1 ヶ所のみ

## References

- 関連 ADR:
  - [[adr/0002-mc-as-edge]] — Bukkit 漏らさない原則
  - [[adr/0021-declarative-gui]] — GUI declarative + intent value 原則
  - [[adr/0027-phase-1-poc-closing-scope]] — forward-only 方針
  - [[adr/0072-pre-paper-sweep-port-commitments]] — Wave 5g port commitment 流儀
  - [[adr/0074-pre-paper-sweep-depth]] — Wave 5h sibling (Hologram / GUI 拡張 / Tutorial / Moderation)
- 関連 docs:
  - `docs/RTM-PARITY-TRACKER.md` — Cat 4 (GUI) / 新 Cat (Dialog) 行
  - `docs/IMPLEMENTATION-ROADMAP.md` — Wave 5i entry (Tier 6.7)
- Code:
  - `plugin/core/.../domain/dialog/` (8 file: DialogSpec / DialogBody / DialogInput / DialogButton / DialogAfterAction / DialogResponse / DialogId / DialogCloseReason)
  - `plugin/core/.../application/port/outbound/IDialogGateway.kt` + DialogError sealed
  - `plugin/core/.../application/service/InMemoryDialogGateway.kt`
  - `plugin/core/.../application/gui/GuiSpecBuilder.kt` (DSL primitives + 4 slot builder)
  - `plugin/core/.../application/gui/DialogSpecBuilder.kt` (Dialog DSL + 2 example)
  - `plugin/test-fixtures/.../FakeDialogGateway.kt`
  - `tools/architecture-test/.../ArchitectureTest.kt` (Wave 5i +1 rule)
