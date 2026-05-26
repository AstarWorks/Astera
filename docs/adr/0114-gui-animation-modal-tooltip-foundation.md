---
status: Accepted
date: 2026-05-27
accepted_date: 2026-05-27
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0114: GUI animation + Modal + Tooltip-extension foundation (GUI depth primitive)

## Status

Accepted (Wave 5gui-depth landed 2026-05-27)

## Context

Wave 5h `GuiSpec` DSL は **基本 slot 構築のみ** で、Phase 1 — Phase 4 想定の
UI 体験で頻出する 3 つの primitive が **完全欠落** していた:

- **Slot / hover / scroll animation** — Wave 5h `Easing` enum (32 curves)
  は landed しているが、それを GUI slot の visual transition に hook する
  port が無い。Wave 5flex profile card の slot reveal / Wave 5refine
  refinement toast / Wave 5e quickbar swap / Wave 5bet odds tick が暗黙に
  前提とする。
- **Modal / popup / overlay** — Wave 5h `GuiSlot.Confirmation` (anvil GUI
  prompt) はあるが、それは **slot 1 個分の confirm UI** であり、本格的な
  「modal 全体」「toast 通知」「hover tooltip overlay」「Wave 5e quickbar
  popup」のような layered overlay primitive は無い。
- **Per-viewer tooltip dynamic extension** — Wave 5h `GuiSlot.Static.tooltip`
  は **i18n key の static list** で、per-viewer の動的 tooltip injection
  (Wave 5refine refinement tier / Wave 5flex glow rarity badge /
  Wave 5bet betting status / Wave 5pve-survival gathering cooldown) を行う
  hook が無い。

2026-05-27 audit (`.claude/plans/wave-5g-core-hardening-giggly-sunset.md`
§Round 6) で上記が定量化された。Wave 6 Paper sweep に進む前に Core 側で
port を land しないと、Paper adapter で ad-hoc な animation / modal /
tooltip 実装が散らばるリスク。

**設計選択の制約**:
- Bukkit `EntityMetadata` / `ItemMeta.lore()` / anvil-prompt API / overlay
  packet は domain 直接 import 禁止 (Konsist `core domain stays pure`).
- Wave 5h `Easing` enum (32 curve) を **必ず流用** — 新 easing curve は作
  らない (Wave 5cinematic で確立した方針)。
- Wave 5h `GuiSpec` / `GuiSlot` を **必ず流用** — 並列 GUI 体系を作らない。
- Wave 5j `HudGlyphRef` + `HudColor` を **必ず流用** — `TooltipLine.Image` /
  `TooltipLine.Text` / `TooltipLine.ProgressBar` の表現は HUD と共通。
- Phase 1 PoC closing posture (`.claude/CLAUDE.md` §Pre-release posture) を
  最大限活用 — fallback shim / dual-decoder / `@Deprecated` は残さない。
- Modal callbacks は lambda 禁止 (`GuiSlot` と同じ理由 — serialisable /
  replayable / non-Paper renderable な declarative spec)。`GuiIntent` value
  を使用。
- DB migration なし (session-scoped、Phase 1 では runtime state only)。
  main 最大 V43 → 本 wave V44+ を予約しない。
- Sub-agent harness 内では `rtm-sync` / `astera-reviewer` subagent / Agent
  tool が呼べないので Phase B は orchestrator solo sequential、Phase C は
  手動 RTM update + 手動 review。

リサーチ根拠: Hypixel toast notification system (per-player auto-dismiss
queue)、League of Legends item hover tooltip (per-viewer 統計 + skill
ranks)、Minecraft Bedrock UI (animation curve for inventory slot
reveal)。

## Decision

Wave 5gui-depth で **Core 単体に foundation port + in-memory impl までを
land** する。Paper adapter (EntityMetadata animation / anvil-prompt modal /
overlay packet / tooltip render hook) は Wave 6 で別建て。

### Domain (`core.domain.guidepth.*`、10 file)

GUI animation:
- `AnimationHandle` value class (`String` id、blank reject)。
- `SlotAnimation` sealed:
  - `Fade(fromAlpha, toAlpha, durationTicks, easing: Easing)` — alpha
    `0.0..1.0` enforce、negative duration reject。
  - `Slide(fromSlot: SlotIndex, toSlot: SlotIndex, durationTicks, easing)`
    — slot-index translation (visual only、actual slot map untouched)。
  - `Bounce(amplitude: Double, durationTicks)` — fixed
    `EASE_OUT_BOUNCE` semantics、amplitude > 0 enforce。
  - `Pulse(scaleMin, scaleMax, durationTicks)` — scale > 0 + max >= min
    enforce。
- `HoverAnimation` sealed:
  - `Highlight(overlayColorArgb, overlayAlpha, durationTicks)` — alpha
    `0.0..1.0` enforce。
  - `Scale(peakScale, durationTicks, easing: Easing)` — peakScale > 1
    enforce (visible grow)。

Modal:
- `ModalHandle` value class (`String` id、blank reject)。
- `AnchorPosition` sealed:
  - `Center / TopLeft / TopRight / Cursor / NearSlot(slotIndex)`。
- `GuiModalSpec` sealed 5-variant:
  - `Confirmation(titleKey, messageKey, onConfirm: GuiIntent, onCancel: GuiIntent)`
    — confirm/cancel button modal。
  - `Notification(titleKey, messageKey, dismissAfterTicks?: Long)` —
    auto-dismiss toast、null = 手動 close only、`>= 0` enforce。
  - `QuickbarSelector(slots: List<GuiSlot>, onSelect: GuiIntent)` — Wave
    5e quickbar popup。non-empty + `<= 18` enforce。
  - `TooltipOverlay(content: TooltipContent, anchorPos: AnchorPosition)`
    — hover tooltip overlay。
  - `Custom(content: GuiSpec)` — generic overlay escape hatch。

Tooltip:
- `ItemCategory` value class (lowercase kebab-case、`ItemId` 同じ regex —
  `[a-z][a-z0-9-]*`)。Free-form categorisation tag。
- `TooltipLine` sealed:
  - `Text(messageKey, color: HudColor = WHITE)`。
  - `Image(textureRef: HudGlyphRef)` — Wave 5j 流用。
  - `ProgressBar(percent, fillColor: HudColor)` — percent `0.0..1.0`
    enforce。
- `TooltipContent(lines: List<TooltipLine>)` — non-empty enforce。
- `TooltipScope` sealed:
  - `AllItems / ByRarity(Rarity) / ByCategory(ItemCategory) / ByItem(ItemId)`。

### Cross-plugin events (`core.domain.event.*`、5 new)

- `SlotAnimationStarted(playerId, guiId, slotIndex, handle, durationTicks,
  animationKind: String, atTick)` — `animationKind ∈ {"fade", "slide",
  "bounce", "pulse", "hover", "scroll"}` (snake_case discriminator)。
- `SlotAnimationCompleted(playerId, guiId, slotIndex, handle, atTick)` —
  natural duration 完了。
- `SlotAnimationCancelled(playerId, guiId, slotIndex, handle, reasonKind:
  String, atTick)` — `reasonKind ∈ {"explicit", "gui_closed",
  "superseded"}` (`CinematicEnded` 同 pattern)。
- `ModalShown(playerId, handle, modalKind: String, atTick)` — `modalKind ∈
  {"confirmation", "notification", "quickbar_selector", "tooltip_overlay",
  "custom"}`。
- `ModalDismissed(playerId, handle, reasonKind: String, atTick)` —
  `reasonKind ∈ {"explicit", "bulk_dismiss", "auto_expired", "player_quit"}`。

`SlotAnimationCompleted` と `SlotAnimationCancelled` は同一 handle につ
いて **mutually exclusive**。

### Outbound ports (`core.application.port.outbound.guidepth.*`、4 new)

- `IGuiAnimationController`:
  - `animateSlot(playerId, guiId, slotIndex, animation: SlotAnimation):
    Result<AnimationHandle, GuiAnimationError>` — 既存 `(player, gui, slot)`
    triple があれば silent supersede (cancel event with reasonKind =
    "superseded")。
  - `animateHover(playerId, guiId, slotIndex, hoverAnimation:
    HoverAnimation): Result<AnimationHandle, GuiAnimationError>` — 同 triple
    上で slot animation と排他。
  - `animateScroll(playerId, guiId, fromScroll, toScroll, durationTicks,
    easing: Easing): Result<AnimationHandle, GuiAnimationError>` — GUI-wide
    (sentinel `SlotIndex(0)`)、duration `>= 0` enforce。
  - `cancel(handle): Result<Unit, GuiAnimationError>` — idempotent、explicit
    reason。
- `IGuiModalGateway`:
  - `showModal(playerId, modal: GuiModalSpec): Result<ModalHandle,
    GuiModalError>`。
  - `dismissModal(handle): Result<Unit, GuiModalError>` — idempotent。
  - `dismissAllForPlayer(playerId): Result<Unit, GuiModalError>` —
    bulk_dismiss reason per handle。
  - `activeModals(playerId): List<ModalHandle>` — show-order preserved。
- `ITooltipExtensionProvider` (fun interface):
  - `extend(item: ItemId, viewer: PlayerId): List<TooltipLine>` —
    side-effect-free、per-tick render path。
- `ITooltipRegistry`:
  - `register(scope: TooltipScope, provider: ITooltipExtensionProvider)`。
  - `lookup(item, viewer): List<TooltipLine>` — bucket
    AllItems → ByRarity → ByCategory → ByItem order、within bucket は
    registration order。
  - `rarityOf(item): Rarity?` / `categoriesOf(item): List<ItemCategory>` —
    resolver expose (injected at constructor)。

`GuiAnimationError` / `GuiModalError` sealed (`UnknownPlayer /
InvalidDuration / Platform`)。すべて `Result<T, sealed Error>` — Konsist が
application 層の raw throwable 禁止を強制。

### In-memory services (`core.application.service.guidepth.*`、3 file)

- `InMemoryGuiAnimationController(eventBus, tickClock)`:
  - active 2-way map (handle ↔ `ActiveAnimation(playerId, guiId,
    slotIndex)`) で per-triple uniqueness 保証。
  - supersede 時に prior handle を cancel event with reason "superseded"
    で fire。
  - **scheduler 持たない** — `simulateComplete(handle)` test helper を
    expose、Paper adapter (Wave 6) で `IScheduler.runLater(durationTicks)`
    注入 (Wave 5cinematic `InMemoryCameraGateway.freeze` precedent —
    lifetime 安全のため意図的)。
  - `activeCount()` test helper。
- `InMemoryGuiModalGateway(eventBus, tickClock)`:
  - `byHandle: handle → playerId` + `byPlayer: playerId →
    CopyOnWriteArrayList<handle>` 二重 index で show-order 保持。
  - `dismissAllForPlayer` は bulk fire (per handle で
    `ModalDismissed(reasonKind = "bulk_dismiss")`)。
  - **scheduler 持たない** — `Notification.dismissAfterTicks` auto-dismiss
    は Paper adapter responsibility (`simulateAutoExpire(handle)` test
    helper で auto_expired path をテスト)。
- `InMemoryTooltipRegistry(rarityResolver, categoriesResolver)`:
  - 4 bucket (`allItems / byRarity / byCategory / byItem`) を
    `CopyOnWriteArrayList` で保持 — hot path `lookup` が
    `register` と race しない。
  - Phase 1 wiring: `AsteraCorePlugin` で `rarityResolver = { null }` +
    `categoriesResolver = { emptyList() }` — `ByRarity` / `ByCategory`
    scope は **Wave 5refine / Wave 5flex / Wave 5pve-survival hookup まで
    fire しない**。`AllItems` / `ByItem` は無条件で動作。

### Konsist +1 rule (47→48)

`gui-depth domain stays pure (ADR-0114)` — `core.domain.guidepth.*` が
Bukkit / kotlinx.serialization / exposed / postgres / sql / lettuce / koin
を import するのを禁止。**JOML は許可** (Wave 5h-fix precedent — pure-math、
no Minecraft coupling、Phase 4 web admin renderer のための余地)。

### Plugin wiring

`AsteraCorePlugin.onEnable` で 3 ports を priority=0 register (Wave
5cinematic block の直後):
- `IGuiAnimationController` (InMemory)
- `IGuiModalGateway` (InMemory)
- `ITooltipRegistry` (InMemory、`{ null }` + `{ emptyList() }` resolver
  seeds — feature plugin hookup で single-line swap)

`SERVICE_NAMES` companion list に 3 entry 追加。

### Tests (10 test classes、47 tests)

- Domain: `SlotAnimationTest` (7) / `HoverAnimationTest` (3) /
  `GuiModalSpecTest` (6) / `TooltipLineTest` (3) / `ItemCategoryTest` (5) /
  `HandleTest` (4) = **28**。
- Service: `InMemoryGuiAnimationControllerTest` (9) /
  `InMemoryGuiModalGatewayTest` (8) / `InMemoryTooltipRegistryTest` (8) =
  **25**。

すべて Bukkit harness 不要 (純 JVM)。`./gradlew check` green、Konsist 48
rules pass、7 plugin shadowJars 全 green。

## Consequences

### Positive

- **Wave 5flex profile card reveal / Wave 5refine refinement toast /
  Wave 5e quickbar swap / Wave 5bet odds tick** が `IGuiAnimationController`
  接続可能になり、Wave 6 Paper sweep で「port は既にある」状態。
- **Wave 5e quickbar popup / Wave 5refine confirm modal / Wave 5shop
  purchase confirm** が `IGuiModalGateway.showModal(player,
  Confirmation/QuickbarSelector/...)` で 1 行 fire 可能 — feature plugin
  から Core 経由で modal 制御。
- **Wave 5refine refinement tier / Wave 5flex glow rarity / Wave 5bet
  betting status / Wave 5pve-survival gathering cooldown** の per-viewer
  tooltip injection が `ITooltipRegistry.register(scope, provider)` で
  achievable — feature plugin が `ITooltipExtensionProvider` を実装する
  だけ。
- **Wave 5h `Easing` 流用** で `SlotAnimation.Fade / Slide` / `HoverAnimation.Scale`
  / `IGuiAnimationController.animateScroll` の curve 選択が 32 種類 —
  新しい easing math を増やさず carry over。
- **Wave 5h `GuiSpec` / `GuiSlot` 流用** で `GuiModalSpec.Custom(content:
  GuiSpec)` / `QuickbarSelector(slots: List<GuiSlot>)` が並列 GUI 体系を
  作らずに済む — modal は GUI の上位概念ではなく、GUI を **包含する** 概念。
- **Wave 5j `HudGlyphRef` + `HudColor` 流用** で `TooltipLine` が HUD と
  同じ glyph + color vocabulary を共有 — tooltip と HUD のデザイン整合性
  が型レベルで保証。
- **Konsist 48 rules** で gui-depth の Bukkit-free が CI で永続的に
  enforce。

### Negative / Trade-offs

- In-memory `InMemoryGuiAnimationController` は **duration-driven
  completion を持たない** — Paper adapter (Wave 6) で
  `IScheduler.runLater(durationTicks) { fireCompleted(handle) }` を注入す
  る必要あり (Wave 5cinematic `InMemoryCameraGateway.freeze` と同 trade-off)。
  Test では `simulateComplete(handle)` helper を expose。
- In-memory `InMemoryGuiModalGateway` も同様に **`Notification.dismissAfterTicks`
  auto-dismiss を持たない** — Paper adapter (Wave 6) で
  `IScheduler.runLater(dismissAfterTicks) { dismissModal(handle) }`。
  Test では `simulateAutoExpire(handle)` helper。
- `InMemoryTooltipRegistry` の `rarityResolver / categoriesResolver` は
  **Phase 1 default で `null` / `emptyList`** — Wave 5refine / Wave 5flex
  / Wave 5pve-survival が hookup するまで `ByRarity` / `ByCategory` scope
  の provider は fire しない。`AllItems` / `ByItem` は無条件で動作するの
  で **silent broken** にはならないが、scope を ByRarity で register す
  る feature plugin は resolver hookup wave まで待機。
- `ItemCategory` は free-form value class — global enum registry は持たない。
  category 名の typo は **regex check (kebab-case) でしか catch されない**。
  将来 `IItemCategoryRegistry` を作る可能性は ADR-0114 follow-up で考慮。
- modal の **stacking order semantics** は in-memory では show-order の
  list だけで、z-index control は持たない (Wave 6 で Paper-side rendering
  layer が決定)。Phase 1 では「最後に showModal したのが最前面」と仮定。

### Mitigations

- Paper adapter (Wave 6) で auto-completion / auto-dismiss / per-viewer
  resolver hookup を **port semantics の変更なしで** 追加できる設計
  (priority=100 で in-memory を shadow)。
- `ItemCategory` の typo 早期検出は Wave 5refine 等が register 時に
  自前で validation できる (or 将来 `IItemCategoryRegistry` 導入時に
  catch)。
- z-index control が必要になったら `GuiModalSpec` に `zIndex: Int = 0`
  field 追加 + `dismissAllForPlayer` 拡張で post-hoc 対応可能 (port
  semantic 不変、Phase 1 PoC posture 上 breaking change は受容)。

## Alternatives Considered

- **Alt A — Lambda callbacks for modal `onConfirm / onCancel`**: domain が
  function type を抱える = serialisable でなくなる + non-Paper renderable
  でなくなる。`GuiIntent` value path に統一が clean (`GuiSlot` 既存パター
  ンの拡張)。却下。
- **Alt B — `SlotAnimation` を flat `data class SlotAnimation(kind: enum,
  params: Map)`** にする: type-safe でなくなる + variant 追加で payload
  shape が拡散する。sealed hierarchy で variant 単位の constructor
  validation が clean。却下。
- **Alt C — `ITooltipRegistry` を `IItemRegistry` の subset として
  Wave 5h に retrofit**: registry の責務肥大化 + ItemSpec が per-viewer
  state に依存することになる (ItemSpec は pure metadata)。registry を分
  離する方が SRP 守れる。却下。
- **Alt D — Animation を Wave 5cinematic `ICinematicPathPlayer` で兼用**:
  cinematic は player camera 動作、gui-depth は GUI slot 動作で **scope
  が orthogonal**。兼用すると `CinematicPath.waypoints` に GUI slot が混
  ざる awkward な API になる。port を分離する方が両方 clean。却下。
- **Alt E — Modal を `IDialogGateway` (Wave 5i) で兼用**: `IDialogGateway`
  は **vanilla dialog packet (Mojang dialog system)** 専用で、Astera が
  自由に layout を決める modal とは別物。Mojang dialog 制約 (button slot
  数 / text length) が overlay にも波及する。port を分離する方が両方
  clean。却下。
- **Alt F — `TooltipScope.ByRarity` / `ByCategory` の resolver を hard-code
  `IItemRegistry` 依存にする**: `InMemoryTooltipRegistry` が `IItemRegistry`
  を強依存 = fake が複雑 + 循環依存リスク (item plugin が tooltip を register
  するのに、tooltip registry が item registry を要求する loop)。lambda
  injection で **依存方向を逆転** する方が clean。採用。
- **Alt G — Easing を gui-depth 用に新規定義**: Wave 5h `Easing` enum (32
  curve) が animation / display / cinematic / gui-depth 共通使用に十分。
  重複定義すると "どっちの Easing を使うのか" が API surface に表れて
  confusing。Wave 5cinematic と同じ判断。却下。

## References

- 関連 ADR:
  - ADR-0079 (Wave 5h hologram / Display entity foundation、`GuiSpec` /
    `GuiSlot` の DSL 基盤)
  - ADR-0097 (Wave 5j Custom HUD foundation、`HudGlyphRef` / `HudColor`
    流用元)
  - ADR-0110 (Wave 5cinematic、`Easing` 流用方針 + in-memory
    scheduler-free pattern の precedent)
  - ADR-0111 (Wave 5world-events、`reasonKind` snake_case discriminator
    pattern を継承)
- 関連 docs:
  - [[architecture/principles]]
  - [[RTM-PARITY-TRACKER]] (GUI animation + Modal + Tooltip extension
    foundation row)
  - `.claude/plans/wave-5g-core-hardening-giggly-sunset.md` §Round 6
