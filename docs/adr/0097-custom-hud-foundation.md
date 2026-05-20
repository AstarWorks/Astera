---
status: Accepted
date: 2026-05-20
deciders: Astera core
supersedes: null
superseded_by: null
---

# ADR-0097: Custom HUD foundation (texture font + Resource Pack push)

## Status

Accepted

## Context

Astera は Phase 1 PoC で **mana / stamina / cooldown / star count / match
timer / boss health / minimap-like indicators** を player に常時表示する
必要がある。バニラ Minecraft のデフォルト HUD (hunger bar / xp bar /
health bar) を流用するのは現実的でない:

- **Vanilla XP bar は Astera Level/Norma に置換** (Wave 5e ADR-0058 ✕ 5g)。
  XP bar の数値表示はバニラ仕様で 0..30 までしか表示できず、Astera の
  経験値カーブと一致しない。
- **Vanilla hunger bar は Astera では使わない** (本 plan §「取捨選択原則」)。
  satisfaction 駆動の food system は今回不要。
- **Action bar / boss bar / scoreboard sidebar / title-subtitle** だけでも
  bare text 表示は可能だが、glyph / icon / ベクター進捗ゲージ表示は不可。
  Minecraft の HUD は本質的に **テキストレンダラー** で、画像 (sprite) を
  表示する公式機構は存在しない。

業界標準の解は **Negative Space Font 技法** (Resource Pack の `font.json`
で Private Use Area の Unicode codepoint に PNG glyph を割り当て、本来の
advance とは別に **負の advance** を持つ spacer glyph で reverse-advance
してアイコン重ね合わせ・進捗ゲージを構築する) — 既に Hypixel /
WynnCraft / MineHut の majority server で使われており、必要 prerequisite
は **Resource Pack の push** (Player.setResourcePack) のみ。

Wave 5j ではこの技法を Astera の vendor-neutral 階層に正しく載せる
foundation を作る。**実装は Wave 6b Paper adapter に分離** し、Wave 5j は
domain + port + DSL + in-memory impl + 2 example layout (mana bar / star
counter) + content/hud font JSON のみを含める。

### 制約と選択肢

1. **domain は Bukkit/Adventure 完全非依存** にしたい (ADR-0001 / ADR-0002
   の hex 規約)。`HudComponent` / `HudLayout` は pure Kotlin sealed のみ。
   Adventure `Component` への変換は `HudRenderer` (application layer) で行う。
2. **font.json の codepoint allocation** は明示的 (固定値) でも自動
   (registry の `nextAvailableUnicodeChar`) でもいい — Wave 5j は **両方
   support**。content/hud/*.json で provider 単位に固定 codepoint を持ち、
   ad-hoc 用は `HudGlyph.nextAvailableUnicodeChar` でランタイム allocation。
3. **Resource Pack push** は Player.setResourcePack だけだと UX が貧弱
   (受諾/拒否 UI なし、SHA-1 検証必須)。Wave 5j は domain で
   `ResourcePackSpec(url, sha1Hash, required, promptKey)` を持ち、Wave 6b
   adapter が Paper API でこれを設定。

## Decision

`core.domain.hud.*` に **vendor-neutral な HUD primitive sealed tree** を
置く。`IHudGateway` / `ICustomFontRegistry` を application port として提供。
Adventure / Paper 依存は全て `HudRenderer` + Wave 6b `PaperHudGateway` に
isolate する。Resource Pack push は `IHudGateway.pushResourcePack` 経由で
domain 起動。Negative Space Font 技法は `HudComponent.Spacer(widthPx)` を
**負値許可** で表現し、Wave 6b adapter が font.json の負 advance glyph に
mapping する。

### 構成詳細

**Sealed tree** (vendor-neutral):

```
HudComponent
  ├ Text(messageKey, color?)
  ├ Glyph(textureRef, widthPx, ascent)
  ├ ProgressBar(percent, fillGlyph, emptyGlyph, widthSegments) // currentSegments() で fill 数算出
  ├ Composite(parts, spacing)
  └ Spacer(widthPx)  // 負値許可 (negative-space font 技法)

HudLayout
  ├ ActionBar(component, ttlTicks)
  ├ BossBarSlot(slot 1..4, component, color, style, progress)  // withProgress() helper
  ├ Sidebar(titleKey, lines 1..15)
  ├ TabList(header?, footer?)
  └ Subtitle(component, fadeIn, stay, fadeOut)  // sum > 0
```

**Glyph / Resource Pack**:

- `HudGlyph(textureRef, unicodeChar, widthPx, heightPx, ascent)` — BMP PUA
  (U+E000..U+F8FF) 強制、`nextAvailableUnicodeChar(used)` で deterministic
  allocation。
- `HudGlyphRef("category/name")` value class — `^[a-z][a-z0-9_-]*(/[a-z0-9_-]+)*$`
  regex validation。
- `HudColor` Byte-backed RGBA value class + 17 palette constant
  (WHITE/BLACK/RED/...)、`fromHex/toHex` round-trip。`Adventure.TextColor`
  との bridge は `HudRenderer` で。
- `ResourcePackSpec(url, sha1Hash 40-hex, required, promptKey)` — Wave 6b
  adapter `Player.setResourcePack(url, sha1Hash, required, promptComponent)`。

**Port** (`core.application.port.outbound.IHudGateway`):

```kotlin
public interface IHudGateway {
    public fun showFor(playerId, layout): Result<HudHandle, HudError>
    public fun updateFor(handle, layout): Result<Unit, HudError>
    public fun clearFor(playerId, region: HudRegion): Result<Unit, HudError>
    public fun pushResourcePack(playerId, pack: ResourcePackSpec): Result<Unit, HudError>
    public fun activeFor(playerId): Collection<HudHandle>
}
```

`HudError` sealed: `UnknownPlayer / UnknownHandle / InvalidGlyph(ref) /
ResourcePackPushFailed / Storage`。

`ICustomFontRegistry` は `lookup(ref) / all() / register(glyph)`
(duplicate codepoint reject)。

**Default impl**:
- `InMemoryHudGateway(eventBus, scheduler)` — Map + scheduler-aware TTL
  auto-clear、4 種 DomainEvent publish (HudShown/HudUpdated/HudCleared/
  ResourcePackPushRequested)。
- `InMemoryCustomFontRegistry` — duplicate codepoint reject。
- `FakeHudGateway` (test-fixtures) — operations history +
  `simulateClientAccepted/DeclinedPack` で UX test。

**Renderer** (`core.application.gui.hud.HudRenderer`):

`HudComponent` → Adventure `Component` 変換:
- `Text(messageKey)` → `IMessageRenderer.render(messageKey, locale)`
- `Glyph(ref)` → `Component.text(glyph.unicodeChar).font(Key.key("astera", "default"))`
- `ProgressBar(percent, fill, empty)` → fill × `currentSegments()` + empty × 残数
- `Composite(parts, spacing)` → join via spacing
- `Spacer(widthPx)` → `HudGlyphRef("space/<widthPx>")` font glyph (Wave 6b
  font.json に `widthPx` 軸の spacer family を pre-generate)。registry に
  該当 glyph なければ ASCII space fallback。

**DSL** (`core.application.gui.hud.HudLayoutBuilder`):

`hudLayout { actionBar/bossBar/sidebar/tabList/subtitle { ... } }` —
`@HudDsl` annotation で scope 衝突防止。`text / glyph / progressBar /
composite / spacer` primitive で nest 構築。

**Example layouts** (`core.application.gui.hud.examples.*`):
- `ManaBarHud(percent, current, max)` — BossBar slot 1, BLUE, NOTCHED_10。
- `StarCounterHud(count)` — ActionBar TTL 20。

**content/hud/*.json** (Minecraft font format 互換、Wave 6b adapter が
`assets/astera/font/default.json` に merge):
- `mana-bar.json` — 3 provider (icon U+E000, fill U+E001, empty U+E002)
- `star-counter.json` — 1 provider (star U+E010)

**Konsist rule** (`tools/architecture-test`):
- 新規 `HUD domain must not import Bukkit Paper Adventure (ADR-0097)` —
  `core.domain.hud.*` から `org.bukkit.*` / `io.papermc.*` /
  `org.spigotmc.*` / `com.destroystokyo.paper.*` / `com.mojang.*` /
  `net.kyori.adventure.*` を forbid。`org.joml.*` は ADR-0075 §JOML
  原則で許可。

## Consequences

### Positive

- **Vanilla HUD 全部置換** が `HudLayout` + Resource Pack で1本道に。
  Mana bar / star count / match timer / boss health / cooldown ring 全て
  同一 abstraction で表現。
- **Vendor-neutral**: domain test は Paper harness 無しで全部動く。Adventure
  bridge は `HudRenderer` 1 か所に集中、Wave 6b で Paper adapter を書く時
  domain は触らない。
- **Negative Space Font 技法を first-class** に組み込んだ:
  `HudComponent.Spacer(widthPx)` が負値許可、Wave 6b adapter は font.json
  spacer family を pre-generate するだけ。
- **DSL**: server admin / content author が新 HUD layout を `hudLayout {
  bossBar { ... } }` で書ける。Kotlin 静的型 + IDE 補完。
- **Resource Pack push を domain で表現** したので、push 失敗時の
  `HudError.ResourcePackPushFailed` を domain logic で handle 可能 (Wave 6b
  adapter が Paper の resource pack accept/decline callback を
  `ResourcePackAcceptedEvent` に変換)。

### Negative / Trade-offs

- **Resource Pack 配信 host が要る**: Wave 5j では `ResourcePackSpec.url`
  は HTTP(S) のみ受け付ける。Phase 1 PoC では local file server (Studio
  サーバ自体が serve するか、別 nginx/MinIO) — production CDN host は
  Phase 4 Wave 8a で別 ADR。
- **Player 側 client が pack DL を拒否すると HUD 表示できない** —
  `ResourcePackSpec.required = true` ならば Paper が kick、`false` なら
  バニラ HUD fallback。fallback 時の挙動定義は Wave 5o (Respawn flow) で
  別途整理。
- **font.json codepoint exhaustion** — BMP PUA は 6400 codepoint。
  Phase 1 PoC では 50-100 で十分、Phase 5 で別 ADR が要る。
- **HudComponent.Spacer の負値は font.json の spacer family を
  pre-generate** しないと renderer fallback (ASCII space) で表示が乱れる
  — Wave 6b adapter 側で必ず -1, -2, -4, -8, -16, -32, -64, -128 全部
  pre-generate する責務。

### Mitigations

- Resource Pack host: Wave 8a で CDN ADR 起草、それまでは local serve で
  PoC 進行 OK (`http://localhost:8080/...`)。
- Pack reject fallback: `IHudGateway` impl は `HudError.ResourcePackPushFailed`
  を返し、上位 use case (Wave 5o respawn / Wave 5k physiology HUD) で
  vanilla HUD 経路に分岐。
- Codepoint exhaustion: `ICustomFontRegistry.register` は duplicate reject、
  Phase 5 で codepoint pooling / category 分割の ADR (TBD)。
- Spacer family pre-generation: Wave 6b adapter テストで全 8 段階の spacer
  glyph が font.json に存在することを assertion (Wave 6b ADR で明示)。

## Alternatives Considered

- **scoreboard sidebar だけで mana/stamina を text 表示** — text-only。
  glyph / progress bar が表現できず、UX が文字数値のみで貧弱。Astera は
  visual richness を求めるので不採用。
- **PacketEvents で custom HUD packet 送信** — ADR-0017 で
  "no packet protocol manipulation" 決定済。Negative Space Font は
  完全に vanilla packet で動くので Resource Pack だけで十分。
- **MyResourcePack / ItemsAdder などの既存 plugin に依存** — ADR-0009
  ("all external plugins soft-dep") 違反 + 1ライセンス 30-50 USD コスト。
  Astera は font.json 直接生成で OK、external dep 不要。
- **Domain で Adventure `Component` を直接持つ** — ADR-0001 hex 規約違反。
  Wave 6b で別 chat backend (Mojang Chat / Discord bridge) を追加する時
  domain rewrite が必要になる。

## References

- 関連 ADR: [[adr/0001-clean-architecture-with-mc-as-edge]],
  [[adr/0002-vendor-neutral-mc-adapter-layer]],
  [[adr/0009-soft-dep-only-paper-plugin-yml]],
  [[adr/0017-adopt-datadriven-contents-pattern]],
  [[adr/0058-domain-attribute-architecture]],
  [[adr/0075-dialog-domain-and-gui-dsl]]
- 関連 docs: [[architecture/principles]], [[IMPLEMENTATION-ROADMAP]] §Wave 5j
- 外部リンク:
  - Minecraft Wiki — Negative Space Font:
    <https://minecraft.fandom.com/wiki/Resource_pack#Font>
  - Adventure docs — Font: <https://docs.advntr.dev/text.html#fonts>
  - Paper docs — Player.setResourcePack:
    <https://jd.papermc.io/paper/1.21.6/org/bukkit/entity/Player.html#setResourcePack(java.lang.String,java.lang.String,boolean,net.kyori.adventure.text.Component)>
