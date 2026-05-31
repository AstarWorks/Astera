---
status: Accepted
date: 2026-05-31
deciders: Astera core
supersedes: null
superseded_by: null
---

# ADR-0131: Konsist listener-wiring leak prevention rule

## Status

Accepted

## Context

Round 1-14 (Phase 1 PoC closing、50 wave) で、`Annihilation 永遠に終わらない`
型の **silent-drop bug** が round 毎に何度も発見されてきた。代表例:

- **Wave I 5match-wireup-fix (ADR-0126 §A)**: `AnnihilationWinConditionListener`
  クラスは存在し、constructor が `*Plugin.kt::onEnable` 内で
  `val annihilationWinConditionListener = AnnihilationWinConditionListener(...)`
  として呼ばれていたが、その後の
  `subscriptions += annihilationWinConditionListener.subscribe()` が書き忘れ
  られていた。結果として Annihilation match は 50+ commit にわたり
  **永遠に終わらなくなった**。
  - 単体テストは全 pass (listener.handle 自体は正しく書かれていた)
  - plugin 起動も OK (subscribe 漏れは onEnable のロジック上 detect 不可)
  - ローカル動作確認でも気づきにくい (game-mode を選んで実際に最後まで遊ばない
    と露見しない)

- **Wave K 5integration-wire (ADR-0128)** も同型: 7 件の cross-cutting
  integration (combo/focus-fire/target-lock damage bonus 注入、
  Tutorial→Achievement、Quest→Achievement、AchievementUnlocked→ProfileCard、
  MatchEnded→Engagement) が wire されておらず、個別ユニットテストは全 pass
  でも cross-feature integration が dead だった。

これらに共通する**構造的弱点**:

1. `*Listener.kt` クラスは instantiate して `val` に bind するだけでは何も
   起きない。何らかの **subsequent reference** (subscribe / handle / on* /
   bind / start / register(...) / field 代入 等) が `*Plugin.kt::onEnable`
   に必須。
2. Kotlin compiler / 既存 53 Konsist rule / 個別 use-case test は、上記
   "declared but never referenced" pattern を catch できない。Kotlin は
   unused `val` を warning にもしない (ローカル `val` は副作用評価とみなされる)。
   `*Plugin.kt::onEnable` の dead val は完全に silent。

Phase 1 PoC closing 中の現時点で、上記 silent-drop pattern を恒久的に防ぐ
**Konsist rule を 1 つ追加して CI gate にする** ことが、最も低コスト・高 ROI
な investment と判断した:

- **Low risk**: 既存 codebase 全 plugin (`AsteraCorePlugin` / `AsteraItemsPlugin` /
  `AsteraCombatPlugin` / `AsteraMatchPlugin` / `AsteraStatsPlugin` /
  `AsteraEconomyPlugin`) で green に land する状態を確認済み
- **High ROI**: Wave I 型の wire-bug class を CI で 1 回検出すれば足りる

### 副次発見: 既存 ADR-0123 rule の filter bug

本 ADR の実装中に、既存の ADR-0123 rule
(`every plugin onEnable subscribe must be assigned into a subscription
holder`) が `file.name.endsWith("Plugin.kt")` という filter を使っていたが、
Konsist の `KoFile.name` は **拡張子を含まない basename** (`AsteraCorePlugin`)
を返すため、この filter は常に空 set にマッチしていた。すなわち ADR-0123 rule
は **silently 全 pass** していた状態だった。本 ADR と同じ filter (`.endsWith("Plugin")`)
で fix。

Audit β#1 / β#2 (Round 15) の「Konsist 既存 rule の coverage gap」想定と
完全に一致する pattern (rule は存在するが実質 0 ファイルに適用されていた)。
fix 後も全 plugin で green に land。

### Rule 1 (publisher↔subscriber pairing) は本 ADR scope 外

Round 15 audit 段階で当初想定していた DomainEvent publisher↔subscriber
pairing rule も実装を試みたが、現状 149 event が per-type subscriber を欠く
状態であり、その多くは `InMemoryScheduledAnnouncementSystem` の wildcard
`subscribe(DomainEvent::class)` で実質的に消費されているもの・Wave 6 Paper
adapter 待ちの deferred event・Phase 2+ scope の analytics-only event 等
であった。149 件の allowlist baseline を抱えるのは maintainability を損なう
ため、本 ADR では Rule 2 (listener wiring leak) のみを採用。Round 16+
で改めて検討する (allowlist baseline strategy or scope refinement を含めて
再設計)。

## Decision

`tools/architecture-test/src/test/kotlin/com/astarworks/astera/architecture/ArchitectureTest.kt`
に Konsist rule を 1 つ追加 + 既存 ADR-0123 rule の filter bug を fix
(合計 53 → 54 rules; ADR-0123 は count 不変):

### Rule 54: Listener wiring leak prevention (Wave I prevention)

`platform/Astera*Plugin.kt` ファイルを対象に、`val [a-z][A-Za-z0-9]*Listener`
パターンに一致する全ての変数宣言について、以下を verify する:

- 同一ファイル内の宣言以降 (位置的に後) に、その変数名そのもの (`\b<varName>\b`)
  が少なくとも 1 回出現する

満たさない `val xxxListener` 宣言の `file:line: val xxxName` list を AssertJ
message で表示して fail する。

**Subsequent reference が成立する pattern** (rule 落ち方の comment + ADR 本文に
明記):
- `xxxListener.subscribe(...)` — self-subscribe helper
- `eventBus.subscribe(EvtType::class) { xxxListener.handle(it) }`
- `eventBus.subscribe(EvtType::class) { xxxListener.onXxx(it) }` —
  event-specific handler convention (HUD/sidebar listeners 多数)
- `xxxListener.bind(eventBus)` / `xxxListener.start()` / 任意の lifecycle 呼び出し
- `server.pluginManager.registerEvents(xxxListener, this)` — Bukkit Listener
- `someField = xxxListener` — class field 代入 (cancel pattern)
- `someRegistry.register(xxxListener)` — registry 経由 (e.g.
  `bootstrapper.register(...)`)

これらをすべて個別に列挙する代わりに、**"declared but never referenced は
dead code であり Wave I の bug 形と区別不能"** という invariant に集約。

### Filter bug fix (incidental)

`every plugin onEnable subscribe must be assigned into a subscription
holder (ADR-0123)` の filter を `endsWith("Plugin.kt")` → `endsWith("Plugin")`
に修正。これにより ADR-0123 rule が初めて実 plugin file をスキャンする
状態に。fix 後も全 plugin green。

### Out-of-scope (acknowledged limitations)

- `private var xxxListener: T? = null` 形 (Pattern 2 — separated decl +
  later assignment): 稀で、`[onDisable] cancels via [XxxListener.stop]`
  pattern の方が explicit cancel を持つため別 lifecycle で扱う。Phase 1
  に該当 case は Match plugin に 2 件のみ
- `lateinit var` / `by lazy` 等の宣言形: Phase 1 codebase に該当 case なし、
  出現時に case-by-case で対応

## Consequences

### Positive

- Wave I 型 (`val xxxListener = ...` が宣言されたが以降で一切参照されない)
  を CI で必ず catch。 50+ commit に渡って silently 壊れたままになる pattern
  を排除。
- 既存 53 Konsist rule の `subscribe` 系統 (ADR-0123 lifecycle assignment rule)
  と相補的。ADR-0123 は subscribe call 自体の form を、ADR-0131 は declared
  listener が実際に何かに使われているかを check する。
- 副次効果として ADR-0123 rule の filter bug を fix し、本来意図していた
  invariant が今後 enforce される状態に復帰。
- 既存 Astera コードベースで green に land、追加 dep なし、実装 ~70 行
  (1 `@Test fun` + 既存 Konsist KoFile API のみ、java.io.File すら不要)。
- 「subscribe / handle / on* / bind / start / register / field 代入」など
  Astera コードベース実在の様々な wiring convention をすべて受容するため、
  将来 convention が増えても rule 更新は不要。

### Negative / Trade-offs

- **var 宣言系の listener は未カバー**: `private var xxxListener:
  HudUpdateOnMatchTickListener? = null` (line 178-179 in AsteraMatchPlugin.kt)
  のような field 宣言は regex `\bval\s+...` に一致しないため検出されない。
  Phase 1 では 2 件のみ (HUD tick / respawn) で、それらは separate
  lifecycle (assign in onEnable, cancel via `xxxListener.stop()` in onDisable)
  を持つため別カテゴリと整理。
- **"referenced" check は非常に permissive**: `println(xxxListener)`
  や `logger.info("X={}", xxxListener)` のような実質 no-op な reference
  でも rule は pass。一方で Wave I 型 (declared & 一切参照なし) は確実に
  catch される。trade-off として「真の wire bug は catch するが、wire の
  正確性までは保証しない」スタンス。
- **Text-scan based check**: Konsist の KoFile API + 正規表現検査。
  AST-level の receiver tracking は行わないため、`xxxListener` 変数が
  異なる名前にエイリアスされた後 wire される場合
  (e.g. `val foo = xxxListener; foo.subscribe(...)`) も、`xxxListener` が
  代入 RHS として参照される → rule は pass する (false negative 寄り)。
  Phase 1 codebase に問題 case 存在せず。

### Mitigations

- Rule の落ち方は (a) AssertJ withFailMessage で違反 `file:line: val xxxName`
  を全列挙、(b) Rule comment 内に許容される wiring pattern (subscribe /
  handle / on* / bind / start / register / 代入) を全列挙、(c) 違反者が
  即座に修正 pattern を理解できるよう、fail message に ADR-0131 への
  back-reference と Wave I incident summary を埋め込む。
- token-proximity ではなく "宣言以降同一ファイル内のどこかに `<varName>`
  が現れる" の単純な full-tail-search を採用しているため、大きな builder
  pattern や long-method の構造を踏んでも brittle にならない。
- AST-based 厳密検査 (`.subscribe()` call の receiver type tracking、
  unused-value flow analysis) は将来の Konsist version upgrade or
  detekt-rule への移行で対応 (本 ADR 範囲外)。

## Alternatives Considered

- **Alt A: DomainEvent publisher↔subscriber pairing rule (Rule 1)**: 当初
  想定していたが、現状 149 event が per-type subscriber を欠く状態であり
  (wildcard subscriber で消費されているもの、Wave 6 Paper adapter 待ち、
  Phase 2+ scope analytics-only 等を含む)、149 件の allowlist baseline を
  抱えるのは maintainability を損なう。Round 16+ で allowlist baseline
  strategy or scope refinement を改めて検討。
- **Alt B: 厳密に `.subscribe(` / `.handle*(` のみを accept**: 当初実装。
  結果、`.onXxx(` / `.bind(` / `.start(` / `.registerEvents(...,...)` / field
  代入 / registry 渡し等の legit pattern を多数 false-positive。Astera の
  実 wire convention 多様性に対し過剰に narrow。
- **Alt C: `@Wired` annotation を強制し、annotation processor で missing
  wire を detect**: build-time コスト増、kapt/KSP 依存が増える。Konsist
  既存 framework で実現できるため過剰。
- **Alt D: Plugin lifecycle integration test を厚くして、各 listener が
  実際に event を受信することを runtime verify**: 全 listener × 全 event
  の matrix で test が膨張、IT 維持コスト高、CI 時間も増。Konsist の方が
  安価。
- **Alt E: 何もしない (Round 16+ で都度 catch)**: 過去の pattern (Wave I /
  Wave K) の発生頻度から、放置すると Phase 1 ship blocker が再発する確率高。
  rule 追加コスト < bug 1 件の修正コスト。

## References

- 関連 ADR: [[adr/0022-multi-plugin-layout]]、
  [[adr/0123-plugin-lifecycle-subscription-pattern]] (本 ADR で filter bug fix)、
  [[adr/0126-match-listener-wireup-fix]]、
  [[adr/0128-cross-feature-integration-wire]]
- 関連 docs: `.claude/rules/coding-posture.md` (Cross-plugin coordination 節),
  `tools/architecture-test/src/test/kotlin/.../ArchitectureTest.kt`
  (Konsist SSOT)
- Wave I motivating incident: Round 13 5match-wireup-fix
- Future work: Round 16+ で DomainEvent publisher↔subscriber pairing rule
  (本 ADR Alternatives §Alt A) を改めて検討
