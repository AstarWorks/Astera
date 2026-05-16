# Dependency Rules

レイヤ依存方向と import 制約は **CI で強制**する。違反 PR は build fail。

## 1. Gradle モジュール依存ルール

```
:plugin:domain                          ← 何にも依存しない
:plugin:application                     ← :plugin:domain
:plugin:adapter:minecraft-api           ← :plugin:application + :plugin:domain
:plugin:adapter:minecraft-impl-paper    ← :plugin:adapter:minecraft-api (+ paper-api compileOnly)
:plugin:adapter:persistence-postgres    ← :plugin:application
:plugin:adapter:messaging-redis         ← :plugin:application
:plugin:adapter:providers:*             ← :plugin:adapter:minecraft-api + 該当 plugin compileOnly
:plugin:platform-paper-plugin           ← すべての層
:plugin:test-fixtures                   ← :plugin:application + :plugin:domain
```

各サブモジュール `build.gradle.kts` で他層モジュール非依存を明示する。

## 2. Import 禁止ルール (Detekt カスタムルール)

| モジュール | 禁止 import |
|---|---|
| `:plugin:domain` | `org.bukkit.*`, `io.papermc.*`, `org.spigotmc.*`, `org.spongepowered.*`, `dev.jorel.commandapi.*`, `io.lumine.*`, `io.th0rgal.*`, `me.clip.*`, `com.cjcrafter.*` |
| `:plugin:application` | 同上 |
| `:plugin:adapter:minecraft-api` | 同上 (**最重要**: vendor-neutral を守る) |
| `:plugin:adapter:minecraft-impl-paper` | `org.spigotmc.*` (Paper 専用なので) |
| `:plugin:adapter:persistence-postgres` | `org.bukkit.*`, `io.papermc.*` 等 (MC 系) |
| `:plugin:adapter:messaging-redis` | 同上 |

## 3. Konsist (ArchUnit for Kotlin) ルール

`build.gradle.kts` の `check` task に Konsist test を組み込む。例:

```kotlin
@Test
fun `domain layer must not depend on any framework`() {
    Konsist.scopeFromModule("plugin/domain")
        .files
        .assertFalse { file -> file.imports.any { it.startsWith("org.bukkit") || it.startsWith("io.papermc") } }
}

@Test
fun `adapter-minecraft-api must not import Bukkit`() {
    Konsist.scopeFromModule("plugin/adapter/minecraft-api")
        .files
        .assertFalse { it.imports.any { imp -> imp.startsWith("org.bukkit") } }
}
```

## 4. パッケージ命名規則

すべて **`com.astarworks.astera`** プレフィックス (AstarManagement = `com.astarworks.astarmanagement` と統一)。

| 層 | パッケージ |
|---|---|
| domain | `com.astarworks.astera.domain.*` |
| application | `com.astarworks.astera.application.*` |
| adapter | `com.astarworks.astera.adapter.<adapter-name>.*` |
| platform | `com.astarworks.astera.platform.<platform-name>.*` |

## 5. CI 強制

`.github/workflows/ci.yml` で `./gradlew check` を実行。これに含まれる:
- `:plugin:*:test` (JUnit)
- `:plugin:*:detekt` (Detekt + カスタムルール)
- `:plugin:*:konsistTest` (依存ルール)

違反があれば PR は merge できない。
