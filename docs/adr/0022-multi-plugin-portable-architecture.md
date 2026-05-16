---
status: Accepted
date: 2026-05-17
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0022: Multi-plugin Portable Architecture

## Status

Accepted

## Context

Astera は Stage 1 (commit `9c1b9f9`) 時点で、単一の Paper plugin
(`astera-paper-0.1.0-SNAPSHOT.jar`, 25.6 MB) に全機能 (Weapons / Combat /
Match / Stats / Currency / Permission / 等) を同居させていた。Stage 2 で残り
business logic を入れていく予定だったが、2026-05-17 にユーザより根本的な要求:

> Plugin をポータブルにしたい。鯖民が自分のサーバに入れて武器を自作 → テスト
> → 提出するワークフローを成立させたい。Match / Stats / Currency みたいな
> メインサーバだからこそ必要な機能を含まずに、武器テストだけしたい鯖民に
> とっては小さい構成で済む方が望ましい。機能ごとに独立 jar として動かしたい。

単一 jar 構造ではこの要求を満たせない:

- 鯖民が武器テストしたい場合でも 25.6 MB の全機能 jar をロードする必要がある
- 不要機能 (Match scheduler / Stats DB / Currency listener 等) が起動時に
  load イベントを発火し、リソースを消費
- 「武器だけ動かしたい」と言っても「全部入りで動かして使わない機能を無視」しかない

機能粒度で独立 jar に分割すれば:

- 鯖民は **Core + Weapons の 2 jar (最小)** で武器テスト
- サブセット鯖は Core + Weapons + Combat + Match で簡易対戦
- メインサーバは全 7 jar フル装備

各 jar = 1 Paper plugin として、`plugin.yml` の `depend` / `softdepend` で
依存関係を宣言、Core が提供する **ServiceRegistry** で plugin 間の port impl
を疎結合に共有する。

判断軸として AskUserQuestion で 4 つの選択を取った (2026-05-17):

| 軸 | 採用 |
|---|---|
| Stage 2 順序 | Stage 2 (旧計画 α/γ/δ/ε/ζ) を **中断**、先にアーキテクチャ再分割 |
| 分割粒度 | **中粒度 5-7 plugin** (Core / Weapons / Combat / Match / Stats / Economy / Persistence) |
| 通信方式 | **自前 ServiceRegistry + 中央 IDomainEventBus** |
| 鯖民最小単位 | **Core + Weapons の 2 jar** で `/astera give` と発射が動く |

## Decision

1. **Astera を 7 個の独立 Paper plugin に分割する**:
   - **Core** (必須): 共通 domain (Result / MessageKey / PlayerId / ItemId /
     Vec3 / PlayerProfile) / 共通 port (IScheduler / IMessageRenderer /
     IPlayerGateway / IBroadcaster / IItemRegistry / IContentParser /
     ICommandHandler / **IDomainEventBus**) / sealed `DomainEvent` 全 variant /
     `ServiceRegistry` / Paper adapter 基盤 (PaperServer / PaperScheduler /
     PaperBroadcaster / PaperCommandHandler / PaperPlayer / McPlayerGateway /
     BukkitEventAdapter framework) / i18n LanguageLoader / ContentLoader /
     MutableItemRegistry / CommandSpec
   - **Weapons** (鯖民最小): 武器 domain (WeaponId / WeaponSpec / Projectile /
     Gun / DamageProfile) / IWeaponRegistry / ICooldownTracker /
     MutableWeaponRegistry / WeaponLoaderService / InMemoryCooldownTracker /
     FireWeaponUseCase / GiveWeaponUseCase / `/astera give` command / Paper
     click→fire listener
   - **Combat** (任意): Damage{Attempt,Rule,Source,ResolvedDamage} /
     DamageProfile / DamageAttribute / IElementMatrix / YamlElementMatrix /
     IDamageGateway+PaperDamageGateway / Skill{Spec,Id,Trigger,Archetype} /
     ISkillRegistry / SkillLoaderService / StatusEffect{Spec,Instance,Id,Kind}
     / IStatusEffectRegistry / IStatusEffectContainer / ApplyDamage /
     CastSkill / ApplyStatus / TickCooldown use case
   - **Match** (任意): MatchId / Match / MatchPhase / Team / Score /
     MatchStateMachine / IMatchLifecycle / IMatchRepository (in-memory) /
     StartMatch / EndMatch / EntryGame / SwitchPhase use case /
     `/astera match *` commands
   - **Stats** (任意): PlayerStats / Level / Xp / XpCurve / Norma /
     MissionProgress / IPlayerStatsRepository / ILevelService / INormaService
     (in-memory) / RecordKill / RecordDeath / GrantXp / CompleteMission use
     case / `/astera stats *` commands
   - **Economy** (任意): Currency / Wallet / WalletError / ICurrencyLedger /
     in-memory ledger / Deposit / Withdraw / Transfer use case /
     `/astera currency *` commands。Phase 4 で Shop / EnderChest を追加
   - **Persistence** (任意): Postgres 実装で任意の Repository port を **高
     priority register で上書き**。Exposed + HikariCP + Flyway。Plugin の有
     無に関わらず in-memory で動作 (Persistence 入れたら DB に切替)

2. **Plugin 間通信は Core 提供の `ServiceRegistry` + 中央 `IDomainEventBus`**:
   - 各 feature plugin は `onEnable` で自前の port impl (`IWeaponRegistry`
     等) を Core の `services.register<IWeaponRegistry>(impl, priority = 0)`
     で登録
   - 他 plugin は `services.get<IWeaponRegistry>()` で取得 (最高 priority
     が返る → Persistence plugin の Postgres impl が in-memory を上書き)
   - `IDomainEventBus` は Core が単一インスタンスを保持、publish/subscribe で
     cross-plugin event flow
   - subscriber 不在時は何もしない (= 機能フォールバック、Stats 入れてない
     鯖でも weapon は普通に発射される)

3. **`DomainEvent` は Core に集約**:
   - sealed `DomainEvent` marker + 全 variant (WeaponFired / MatchStarted /
     MatchEnded / PhaseChanged / PlayerKilled / DamageDealt / StatusApplied /
     SkillCast / CurrencyChanged / XpGranted) + 関連 sealed (EndReason /
     CurrencyChangeReason / XpSource) を `core/domain/event/` に置く
   - silly-knuth §5 の sealed pattern matching を維持 (Kotlin の sealed は
     cross-module 不可なので、event の置き場所は Core で統一する)
   - 各 feature plugin は event を publish / subscribe するが定義はしない

4. **Gradle module は 1 plugin = 1 module** に簡素化:
   - 旧 11 module (`:plugin:{domain, application, adapter/minecraft-api,
     adapter/minecraft-impl-paper, adapter/persistence-postgres,
     adapter/messaging-redis, adapter/providers/*, platform-paper-plugin}` +
     test-fixtures + architecture-test) → 新 9 module (`:plugin:{core,
     weapons, combat, match, stats, economy, persistence}` + test-fixtures +
     architecture-test)
   - 各 plugin 内部の Clean Architecture layering (`domain/` → `application/`
     → `adapter/` → `platform/`) は **package 構造 + Konsist** で強制 (Gradle
     module 分離は廃止)

5. **`plugin.yml` の depend / softdepend で必須/任意を宣言**:
   - Weapons: `depend: [Astera-Core]`
   - Combat: `depend: [Astera-Core]`, `softdepend: [Astera-Weapons]`
   - Match: `depend: [Astera-Core]`, `softdepend: [Astera-Combat]`
   - Stats: `depend: [Astera-Core]`, `softdepend: [Astera-Match, Astera-Combat, Astera-Weapons]`
   - Economy: `depend: [Astera-Core]`, `softdepend: [Astera-Stats, Astera-Match]`
   - Persistence: `depend: [Astera-Core]`, `softdepend: [Astera-Weapons, Astera-Combat, Astera-Match, Astera-Stats, Astera-Economy]`

6. **鯖民最小単位は Core + Weapons の 2 jar**:
   - `astera-core-<v>.jar` + `astera-weapons-<v>.jar` を入れれば
     `/astera give` + 武器発射 (`WeaponFired` event 発火) が動く
   - Combat 追加で dummy mob にダメージ
   - Match 追加で試合形式テスト
   - すべて漸進的に機能追加可能

## Consequences

### Positive

- **鯖民ユースケース成立**: 武器作成 → テスト → 提出が最小 2 jar で完結
- **機能ごとの独立リリース**: Weapons の bug fix が Combat / Match を再リ
  リースせずに済む
- **不要機能の load なし**: 鯖民の鯖で Match scheduler / Stats DB
  initializer が起動しない
- **テスト分離**: 各 plugin が独立してビルド / テスト可能、CI 並列度向上
- **将来の Folia/Velocity 移行性**: Core の adapter 層を差し替えるだけで
  他 plugin は無影響
- **依存方向の明示化**: `plugin.yml` の depend / softdepend がアーキテクチ
  ャを文書化

### Negative / Trade-offs

- **`plugin.yml` 管理コスト**: 7 個の plugin.yml + depend グラフ管理が必要、
  誤設定で循環依存 or 順序事故。CI で smoke test 必須。
- **クラスローダ分離**: 各 plugin が独自 ClassLoader を持つので、cross-
  plugin の `is` チェックが foot-gun になりうる。`DomainEvent` の sealed
  制約は Core に集約することで回避。
- **`ServiceRegistry` の lookup overhead**: 起動時の
  `services.get<IWeaponRegistry>()` lookup コストが発生 (実害は無視できる
  程度だが明記)。
- **rolling release の coordination**: メインサーバで Combat 2.0 と Weapons
  1.5 のような version skew が発生しうる。**同一 release tag 戦略** で全
  plugin を同期。
- **テスト戦略の調整**: 「Core+Weapons だけで動く」smoke test を CI に
  入れる必要がある。これがないと退化検出が失敗する。

### 既存 ADR との関係

- **ADR-0001** (Clean Architecture with MC as edge): **温存**、Clean
  Architecture は各 plugin 内部で守る (package + Konsist)
- **ADR-0002** (Vendor-neutral MC adapter): **温存**、Core の adapter 層
  (`core/adapter/api/`) が vendor-neutral 抽象 (IMcServer / IMcPlayer 等) を
  提供
- **ADR-0004** (Koin single DI): **超過しない範囲で温存**。Stage 3-改 で
  feature plugin が増えた段階で Koin module per plugin を検討。Stage 2-改
  では手動 ServiceRegistry のみで wiring
- **ADR-0009** (Soft-dep only Paper plugin.yml): **温存 + 強化**、外部
  plugin だけでなく Astera 内部 plugin にも softdepend を使う
- **ADR-0011** (Phase 1 deviations): Phase 1 を超えているので影響限定

### Mitigations

- **Konsist で plugin 間 import 制約を強制**:
  - core が他 feature plugin (weapons/combat/match/stats/economy/persistence)
    を import 禁止
  - 各 feature plugin が sibling feature plugin (例: weapons → match) を
    import 禁止 — Core のみ参照可
  - feature plugin 内部の layer 分離 (`domain/` → `application/` →
    `adapter/` → `platform/` の方向のみ)
- **plugin.yml depend グラフを CI で検証**: Phase 5 で「Core 単独」
  「Core+Weapons」「全 7 plugin」の 3 構成でロード成功を smoke test として
  確認
- **同一 release tag**: 全 plugin を同じ Astera リリース tag (`v0.2.0` 等)
  で同期、version skew を最小化
- **paper server smoke test**: docker で paper を起動、Core+Weapons の 2
  jar 構成で `/astera give @s example-pistol` 成功を CI で実機検証
  (Stage 2-改 末で導入)

## Alternatives Considered

- **単一 jar 継続 + 起動時 feature flag で機能 ON/OFF**:
  - 鯖民が「Match / Stats を OFF にする」設定を書く形。配布物は単一 jar
    のまま。
  - 却下理由: 不要な jar size (25 MB+) が常に転送、起動時に全 class load
    が走る、設定書く必要があり「とりあえず入れる」体験が損なわれる。鯖民
    の最小ワークフローが「2 jar をフォルダに入れて再起動」より煩雑になる。

- **粗粒度 (Core / Game / Persistence の 3 plugin)**:
  - Game に Weapons + Combat + Match + Stats + Currency を同居。鯖民は
    Core + Game。
  - 却下理由: 鯖民の最小単位が「武器 + 全機能含む Game」になり Match
    scheduler 等が起動。粒度が粗すぎてユーザの「武器だけ動かしたい」要求
    を満たさない。

- **細粒度 (10+ plugin、Damage / Skill / Status / Match / Stats / Level /
  Norma / Currency / Shop / EnderChest / Permission をすべて独立)**:
  - 究極的な疎結合。
  - 却下理由: plugin.yml depend グラフが爆発的に複雑化、`Combat` と
    `Skill+Status+Damage` の分割が user 視点で曖昧、CI matrix が n² に膨らむ。
    **中粒度のバランスが現実的**。

- **Bukkit ServicesManager 経由の plugin 間通信**:
  - Paper 標準の `server.servicesManager.register/load` を使う。
  - 却下理由: Bukkit 依存、application 層から直接アクセス不可、adapter
    経由が必須に。Folia / Velocity 移行時のコスト増。**Core 提供の vendor-
    neutral ServiceRegistry** が長期的にレバレッジが大きい。

- **Koin global context で plugin 跨ぎ DI**:
  - 各 plugin が `loadKoinModules()` で同じ Koin context に登録。
  - 却下理由: Stage 2 で Koin 移行を Stage 3-改 へ defer 決定済み。ここで
    Koin を再導入すると判断の逆転。手動 ServiceRegistry の方が学習コストも
    低く、Phase 1 / 2 の「明示的 wiring」の方針と整合。

## References

- [[adr/0001-clean-architecture-with-mc-as-edge]] — Clean Architecture
  原則は各 plugin 内部で温存
- [[adr/0002-vendor-neutral-mc-adapter-layer]] — Core の adapter 層が
  vendor-neutral 抽象を提供
- [[adr/0004-koin-single-di-not-dagger-spring]] — Koin 採用方針 (Stage 3-
  改で plugin 単位 Koin module を検討)
- [[adr/0009-soft-dep-only-paper-plugin-yml]] — soft-dep 原則を Astera
  内部 plugin にも適用
- [[adr/0011-phase-1-implementation-deviations]] — Phase 1 deviation
  policy
- silly-knuth §5 — DomainEvent sealed pattern matching の制約 (本 ADR で
  Core に集約する根拠)
- Plan file:
  `/home/node/.claude/plans/home-node-claude-plans-multi-plugin-refactor.md`
