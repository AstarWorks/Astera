# Layers

Astera の 4 層構造の詳細。

## Lv0: `domain`

### 役割
ゲームの本質的なルールを表現する純粋層。Minecraft / Bukkit / Paper / Folia など、配信エッジに関する知識を一切持たない。

### 含むもの
- **Entity / Aggregate Root**: `Player`, `Match`, `Stage`, `Weapon`, `Wallet`
- **Value Object**: `PlayerId`, `MatchId`, `DamageProfile`, `Vec3`, `Quat`
- **Domain Event**: `PlayerJoinedMatch`, `WeaponFired`, `MatchEnded`
- **Rule (純粋関数)**: damage 計算、level up 判定、siege 進行条件、勝利条件

### 依存可能なもの
- Kotlin stdlib
- `kotlinx.coroutines` (純粋に async 表現のため)
- (それ以外は原則禁止)

### 禁止
- `org.bukkit.*` / `io.papermc.*` / `org.spongepowered.*` / Minecraft 関連すべて
- DB driver / Redis client
- HTTP client
- JOML (座標は domain 独自の薄い `Vec3` で表現し、JOML 変換は adapter 以下で行う)

### テスト
JUnit から純粋関数として高速に呼び出せる。Paper 起動不要。

---

## Lv1: `application`

### 役割
ユースケース (Use Case) を組み立て、port (interface) を定義する。

### 含むもの
- **Use Case**: `StartMatchUseCase`, `FireWeaponUseCase`, `GrantRewardUseCase`
- **Inbound Port**: `ICommandHandler`, `IMatchTrigger` (外部から呼ばれる窓口)
- **Outbound Port**: `IPlayerGateway`, `IWorldGateway`, `IBroadcaster`, `IScheduler`, `ICurrencyLedger`, `IPersistence`, `IMessageRenderer`
- **Application Service**: port 経由で domain を駆動する orchestration

### 依存可能なもの
- `domain`
- Kotlin stdlib / coroutines

### 禁止
- domain と同じ (Minecraft 関連すべて NG)

### テスト
fake port (`test-fixtures`) を inject して JUnit で高速実行。

---

## Lv2: `adapter`

### 役割
Outbound port を実装し、外部 I/O を扱う。Inbound port から application を呼び出すドライバも含む。

### サブモジュール

#### `adapter-minecraft-api`
- **vendor-neutral な Minecraft 概念抽象** (`IMcServer`, `IMcWorld`, `IMcPlayer`, `IMcEvent`, `IMcScheduler`)
- Bukkit / Paper を**一切 import しない**
- `application` の outbound port を `IMc*` を使って実装する binding 層

#### `adapter-minecraft-impl-paper`
- Paper 固有: `IMc*` を Bukkit API で具象化
- Bukkit Event → IMcEvent への変換
- BukkitScheduler 実装

#### `adapter-minecraft-impl-folia` (将来)
- Folia 固有: region scheduler 等

#### `adapter-persistence-postgres`
- `IPersistence` 実装 (jOOQ or Exposed)
- スキーマ migration

#### `adapter-messaging-redis`
- `IBroadcaster` (Pub/Sub) / `ICache` 実装

#### `adapter-providers/{weaponmechanics,oraxen,mythicmobs}`
- 外部 Paper プラグイン適応 (すべて soft-dep + 機能フラグ)
- 該当プラグイン非存在時はバニラ実装にフォールバック

### 依存可能なもの
- `application` + `domain`
- 該当する外部ライブラリ (paper-api compileOnly, postgres driver, lettuce 等)

### 禁止
- `adapter-minecraft-api` のみは Bukkit 系を一切 import しない
- 他 adapter から他 adapter への直接依存 (port 経由でのみ通信)

---

## Lv3: `platform-*`

### 役割
Entry point。サーバーコアごとに 1 モジュール。

### `platform-paper-plugin`
- `AsteraPlugin.kt` (JavaPlugin)
- onEnable / onDisable
- Koin module 結線: `domain` + `application` + `adapter-{minecraft-api,minecraft-impl-paper,persistence-postgres,messaging-redis}`

### `platform-folia-plugin` (将来)
- Folia 用 entrypoint
- `adapter-minecraft-impl-folia` を bind

### `platform-velocity-proxy` (Phase 後半)
- Velocity proxy 機能 (matchmaking 中継等)

### 依存可能なもの
- すべての層

### テスト
smoke test のみ。docker compose で起動して plugin enable が通ることを確認。
