# Roadmap

Astera の Phase 計画。各 Phase は 1〜2 ヶ月目安。

詳細な背景は [計画書 (private)](/home/node/.claude/plans/) を参照。

## Phase 1: 基盤 ✅ (コード/マニフェスト完了、実機検証は実クラスタ次第)

**ゴール**: 疎結合プラグインアーキテクチャ + k8s manifest。ゲーム機能は最小限。

成果 (2026-05-16 時点):

| Deliverable | 状態 |
|---|---|
| Hexagonal 多モジュール構成 (`domain` / `application` / `adapter-*` / `platform-*`) | ✅ |
| レイヤ依存方向を Konsist で CI 強制 (Detekt は JDK 25 対応待ち) | ✅ |
| `adapter/minecraft-api` (vendor-neutral) + `adapter/minecraft-impl-paper` | ✅ |
| 参考武器 (バニラ完結) を YAML 1 枚で in-game に出せる経路 | ✅ |
| `docker/` (Dockerfile + compose) で local Paper + Postgres + Redis 起動 | ✅ (要 docker compose up 実機検証) |
| `deploy/` k8s manifest 44 ファイル (ArgoCD GitOps 構成) | ✅ (要 kind / 実クラスタ検証) |
| `.github/workflows/{ci,image}.yml` CI + GHCR push | ✅ (PR で実行確認は次回) |
| Konsist negative test (domain で bukkit import → fail) | ✅ 実証済 |
| JP/EN i18n リソース | ✅ |
| `docs/` 必須ファイル ([docs/INDEX.md](INDEX.md) 参照) | ✅ |
| README "5-minute setup" + docker README | ✅ |

完了基準: [計画書 §9 + §10](/home/node/.claude/plans/https-github-com-y-ryuzu-ryuzutechnicalm-silly-knuth.md) を参照。

### 次のアクション (Phase 1 残りタスク)

- `docker compose up` を手元で実行 → in-game `/astera give @p example-sword` で動作確認
- ローカル kind cluster で `kubectl apply -f deploy/apps/argocd-apps.yaml` 検証
- `AstarWorks/AsteraPrivate` (非公開 submodule) 作成 + 環境固有 overlay 切出し

## Phase 1.5: 運用底上げ

**ゴール**: 実クラスタ運用に必要な「動的化 + 機密管理」を整える。

- KEDA + matchmaking キュー長 → Game Pod 動的起動 / scale-to-0
- Sealed-Secrets or SOPS を `private/secrets/` で選定し本番秘匿対応
- `kubectl apply -k private/infra/overlays/local/` でローカル kind cluster e2e

完了基準: 本番相当の dev cluster で `make argocd-bootstrap` だけで全 namespace が立つ。

---

## Phase 2 mid: 武器 / スキル / 状態異常 / ダメージ — ドメイン本体

**ゴール**: Phase 2 starter で並べた 3 archetype を「実際に撃てる」ところまで持っていく。RTM の `skill/` `event/` `damage/` パターンを *再設計しながら* 移植。

### Domain (純データ + 純ルール)

- `model/skill/`: SkillId, SkillSpec, SkillArchetype (PROJECTILE / MELEE / AOE / BUFF / TELEPORT / SUMMON), SkillTrigger (LEFT_CLICK / RIGHT_CLICK / SNEAK_LEFT / SNEAK_RIGHT / HOLD / AUTO)
- `model/skill/params/`: sealed `SkillParams` + `MeleeParams` / `ProjectileParams` / `AoeParams` ...
- `model/status/`: StatusEffectId, StatusEffectSpec, StatusEffectInstance, StatusKind (STAR_GAIN / HEAL_AMP / GRAVITY / CT_RATE / SPEED / PARALYSIS / BURN ...)
- `model/damage/`: sealed `DamageSource` (ENVIRONMENT / WEAPON / SKILL / FALL / EXPLOSION), `DamageAttempt`, `ResolvedDamage`
- `rule/`: pure functions — `WeaponDamageRule` (Phase 1 stub を拡張), `ElementMatrixResolver`, `StatusStackingRule`

### Application

- Outbound ports: `ISkillRegistry`, `IStatusEffectContainer` (per-entity), `IDamageGateway` (apply HP delta to a target), `IItemIdentity` (PDC tag read/write), `IElementMatrix`, `IGuiAdapter`, `IMcBossBar`, `IMcScoreboard`
- Inbound port: `ICommandHandler` + sealed `CommandSpec`
- Use cases (still event-light): `LoadContentUseCase` (generic ContentLoader<T>), `ResolveDamageUseCase` (pure), `ApplyDamageUseCase` (port-driven), `CastSkillUseCase`, `ApplyStatusUseCase`, `TickCooldownUseCase`
- i18n: ICU MessageFormat 化 (複数形 / 数値 / 日時書式)

### Adapter

- `adapter-minecraft-api/`: `IMcBossBar`, `IMcScoreboard`, `IMcItem.weaponIdTag` 周りのvendor-neutral 抽象
- `adapter-minecraft-impl-paper/`: `PaperBossBar`, `PaperScoreboard`, PDC ラッパ `PdcTag<T>`, IWorldGateway 実装 (particle / sound / block 操作)
- `adapter-providers/`: WeaponMechanics 実装 (現状空), Oraxen 実装

### Content / config

- `content/skills/example-blast.yaml` (Phase 2 mid デモ skill)
- `content/skills/example-charge.yaml`, `example-buff.yaml`
- `content/balance/element-matrix.yaml` (相性表 data-driven)
- `content/languages/{ja,en}.yaml` 拡張 (skill / status / damage キー)

### Test

- 既存 Konsist ルール + 「`domain/event/` は IBroadcaster を import しない」追加
- domain skill / status / damage の pure-function テスト (90%+ 想定)
- application use case テスト (FakeScheduler / FakeWorldGateway / FakeStatusEffectContainer)

### 解禁ライン

- Event 配信 (IBroadcaster fan-out + sealed dispatcher) はこの Phase の中盤で本格運用開始。Phase 2 mid の前半は **event-light** で進められる範囲を先に固める

---

## Phase 3: Siege Warfare 1 モード本実装

**ゴール**: 1 マップ + 攻撃/防衛非対称 + コア破壊 + 限定構造物破壊。

- Stage / Match lifecycle use case (StartMatch / EndMatch / EntryGame)
- Game mode framework: sealed `GameMode` + listener pattern (RTM の `IGameMode` を redesign)
- Star Generator system (BedWards 風) — domain + application + adapter
- Anomaly (random event) system — sealed `AnomalyType` + scheduler
- Block state / damage tracking (`IBlockStateGateway`) — 試合終了で地形復元
- 結界 (RTM 構想) — Phase 3 後半
- Boss bar + Scoreboard 実装 (Phase 2 mid port をフル活用)
- Siege 用 i18n キー (chat MOTD / HUD)

完了基準: 実 Paper サーバーで 1 マッチが COUNTDOWN → ACTIVE → SUDDEN_DEATH → ENDED まで通る。

---

## Phase 4: UGC マーケット + 投稿導線 + Web / Discord

**ゴール**: 武器・スキン・マップを **non-IT contributor が** Discord / Web から投稿 → 自動レビュー → merge → in-game 配布。

- `adapter-persistence-postgres/` 実装: `IPlayerRepository` / `IMatchRepository` / `IStageRepository` / `ICurrencyLedger` の Exposed 実装
- `adapter-notification-discord/` 実装: Discord webhook 経由の event fanout + bot による content 投稿受付
- Web admin UI (Nuxt 4, AstarSite / AstarFax のパターン流用)
- Vault / Inventory / Reward / Gacha / Shop / Trading の use case 群
- Stats tracking (K/D / win rate / gold spent)
- Level + Norma (mission system) — RTM の `ILevelService` redesign
- UGC moderation (LLM 一次 + human 二次)

完了基準: ブラウザ / Discord から武器 YAML を投稿 → PR 自動生成 → AI レビュー → 人間 approve → main merge → cluster auto-sync → in-game `/astera give` で出る。

---

## Phase 5: Metaverse Place (常設ワールド)

**ゴール**: 試合外も人が滞在する場を作る。

- Plot 永続化 (`IStageRepository` 拡張 / Postgres)
- Plot permission / build right
- SNS 的要素 (アルバム連携 / Discord 連携 / プレイヤー間メッセージ)
- 季節イベント / フェス / 限定試合 (Phase 7 の自動化と接続)
- Tutorial system (RTM 構想の「体験型」を実装)
- 経済深化 (subscription / battle pass の骨格は Phase 4、Phase 5 で深化)

完了基準: 試合 0 の状態でも常時 10+ プレイヤーが滞在しうる UX (Discord 連携 / 建築 / 簡易イベント)。

---

## Phase 6: 生成 AI パイプライン解禁

[[adr/0010]] で Phase 6 まで意図的に保留してきた生成 AI を本格運用。

- `.claude/agents/` 拡充: weapon-designer / texture-generator / sound-generator / model-generator / stage-builder / translator / ugc-reviewer / incident-responder
- `.claude/skills/`: `/new-weapon`, `/regen-texture`, `/translate-key` などの slash command
- 外部 API 統合: Imagen / SDXL (texture), ElevenLabs / Suno (sound), Meshy / Tripo (3D model)
- 安全フィルタ (生成物のモデレーション + 人間レビュー強制ライン)
- API コスト管理 (`docs/ai-pipeline/cost-model.md`)
- リアルタイム LLM 翻訳 (chat → 多言語、RTM 構想の GPT4o-mini を realize)

完了基準: `/new-weapon "雷の弓"` 実行で YAML + テクスチャ + サウンド + 3D モデル + PR が生成される。

---

## Phase 7: 運営自動化 + バトルパス / 季節イベント

**ゴール**: 人間が「方向性」だけ決め、運営の reps は AI に委譲。

- `release-noter` agent: merged PR 集合 → patch note (ja/en) + Discord 投稿
- `incident-responder` agent: Grafana alert → 原因仮説 + runbook 提示
- 季節イベント / フェス / バトルパスの自動進行
- 投票・連続投票ボーナス・招待キャンペーン
- RTM 検定後継 (格付け / 称号)
- アフィリエイト / 武器スキン売買 marketplace の自動 distribution + 還元

完了基準: 1 季節イベントを「方針決定 → AI 実装 → 自動デプロイ → 自動 patch note」の 1 サイクルで回せる。

---

## RTM との関係

各 Phase で RTM から踏襲する mechanic と再設計する architecture の対応は [[architecture/rtm-divergence]] と [[adr/0013-rtm-divergence-policy]] を参照。要点だけ書くと:

- **Keep**: Easing, weapon archetype, loadout slot, element list, generator concept, anomaly concept, provider pattern, YAML content
- **Redesign**: DI (3-stack → Koin), DB (Mongo → Postgres+JSONB), event bus (reflection → sealed), damage events (8 → 1+sealed), skill params (reflection → typed generic), command framework, GUI framework
- **Add**: Result<T,E>, MessageKey, Konsist, ADR archive, GitOps, generative AI pipeline, Discord/Web 投稿導線
