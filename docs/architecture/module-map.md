# Module Map

全 Gradle module の責務・依存・パッケージ一覧。

## モノレポ構成

```
Astera/
├── plugin/                       Gradle multi-module root
│   ├── domain/
│   ├── application/
│   ├── adapter/
│   │   ├── minecraft-api/
│   │   ├── minecraft-impl-paper/
│   │   ├── persistence-postgres/
│   │   ├── messaging-redis/
│   │   ├── notification-discord/        (Phase 後半)
│   │   └── providers/
│   │       ├── weaponmechanics/
│   │       ├── oraxen/
│   │       └── mythicmobs/
│   ├── platform-paper-plugin/
│   └── test-fixtures/
├── content/
├── deploy/
├── docker/
├── docs/
└── private/                      (submodule)
```

## モジュール詳細

### `plugin:domain`
- **パッケージ**: `com.astarworks.astera.domain.*`
- **依存**: Kotlin stdlib, kotlinx.coroutines
- **責務**: 純粋ドメインモデル + ルール
- **テスト**: `./gradlew :plugin:domain:test` (Paper 起動不要)

### `plugin:application`
- **パッケージ**: `com.astarworks.astera.application.*`
- **依存**: `:plugin:domain`
- **責務**: Use case + port 定義

### `plugin:adapter:minecraft-api`
- **パッケージ**: `com.astarworks.astera.adapter.minecraftapi.*`
- **依存**: `:plugin:application`, `:plugin:domain`
- **責務**: vendor-neutral MC 概念抽象 (`IMcServer` 等) + port binding
- **強制**: Bukkit / Paper 系を import しない (Detekt + Konsist)

### `plugin:adapter:minecraft-impl-paper`
- **パッケージ**: `com.astarworks.astera.adapter.paper.*`
- **依存**: `:plugin:adapter:minecraft-api` + `io.papermc.paper:paper-api:26.1.x` (compileOnly)
- **責務**: `IMc*` を Bukkit API で具象化

### `plugin:adapter:persistence-postgres`
- **パッケージ**: `com.astarworks.astera.adapter.persistence.postgres.*`
- **依存**: `:plugin:application` + PostgreSQL JDBC + jOOQ or Exposed
- **責務**: `IPersistence` 等の実装

### `plugin:adapter:messaging-redis`
- **パッケージ**: `com.astarworks.astera.adapter.messaging.redis.*`
- **依存**: `:plugin:application` + Lettuce or Jedis
- **責務**: `IBroadcaster`, `ICache` 実装

### `plugin:adapter:providers:*`
- **パッケージ**: `com.astarworks.astera.adapter.providers.<plugin>.*`
- **依存**: `:plugin:adapter:minecraft-api` + 該当プラグイン (compileOnly)
- **責務**: 外部 Paper プラグイン (Oraxen / MythicMobs / WeaponMechanics) との接続
- **強制**: すべて soft-dep ([[adr/0009]])

### `plugin:platform-paper-plugin`
- **パッケージ**: `com.astarworks.astera.platform.paper.*`
- **依存**: 全層
- **責務**: JavaPlugin entrypoint + Koin module 結線
- **アーティファクト**: `astera-paper-VERSION.jar` (shadowJar)

### `plugin:test-fixtures`
- **パッケージ**: `com.astarworks.astera.testfixtures.*`
- **依存**: `:plugin:application` + `:plugin:domain`
- **責務**: in-memory / fake adapter (テスト用)

## settings.gradle.kts (期待形)

```kotlin
rootProject.name = "astera"
include(
    ":plugin:domain",
    ":plugin:application",
    ":plugin:adapter:minecraft-api",
    ":plugin:adapter:minecraft-impl-paper",
    ":plugin:adapter:persistence-postgres",
    ":plugin:adapter:messaging-redis",
    ":plugin:adapter:providers:weaponmechanics",
    ":plugin:adapter:providers:oraxen",
    ":plugin:adapter:providers:mythicmobs",
    ":plugin:platform-paper-plugin",
    ":plugin:test-fixtures",
)
```

## 将来追加するモジュール

| Module | Phase | 備考 |
|---|---|---|
| `adapter:minecraft-impl-folia` | Phase 2+ | Folia 採用時 |
| `adapter:minecraft-impl-velocity` | Phase 4+ | Proxy 側機能必要時 |
| `adapter:notification-discord` | Phase 4+ | Discord Bot 投稿導線 |
| `platform-folia-plugin` | Phase 2+ | Folia entrypoint |
| `platform-velocity-proxy` | Phase 4+ | Velocity proxy plugin |
