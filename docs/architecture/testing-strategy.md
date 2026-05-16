# Testing Strategy

Astera のテストは「**遅くて壊れやすい統合テストを減らし、速くて頑強な単位テストを多く書く**」を方針とする。Clean Architecture と DIP がそれを可能にする。

## 層別テスト方針

### `domain` (Lv0)

- **テスト種類**: 純粋関数の単位テスト
- **必要なもの**: JUnit 5 のみ
- **実行時間**: ms 単位
- **モック**: 不要 (すべて値で完結)
- **例**: `WeaponDamageRuleTest`, `LevelUpRuleTest`, `SiegeWinConditionTest`

```kotlin
@Test
fun `headshot multiplier doubles damage`() {
    val profile = DamageProfile(base = 10.0, isHeadshot = true)
    assertEquals(20.0, calculateFinalDamage(profile))
}
```

### `application` (Lv1)

- **テスト種類**: Use case のテスト (port を fake で差し替え)
- **必要なもの**: JUnit 5 + `test-fixtures` (in-memory adapter)
- **実行時間**: ms〜数十 ms
- **モック**: MockK は必要に応じて、原則 fake で済ます
- **例**: `StartMatchUseCaseTest`, `FireWeaponUseCaseTest`

```kotlin
@Test
fun `firing weapon broadcasts WeaponFired event`() {
    val broadcaster = InMemoryBroadcaster()
    val uc = FireWeaponUseCase(broadcaster, ...)
    uc.execute(FireRequest(playerId, weaponId))
    assertTrue(broadcaster.published.any { it is WeaponFired })
}
```

### `adapter-minecraft-api` (Lv2)

- **テスト種類**: vendor-neutral binding のテスト (fake `IMc*` を使う)
- **必要なもの**: JUnit + fake `IMcServer` などを `test-fixtures` 提供
- **実行時間**: ms

### `adapter-minecraft-impl-paper` (Lv2)

- **テスト種類**: Paper 固有 logic のテスト
- **必要なもの**: **MockBukkit** (パッケージ毎に必要に応じて) または PaperMC test framework
- **実行時間**: 秒
- **対象**: Bukkit Event → IMcEvent 変換、Paper 固有 API 呼出し
- **使わない**: 実際の Minecraft クライアント

### `adapter-persistence-postgres` (Lv2)

- **テスト種類**: Testcontainers で PostgreSQL を立ててリポジトリ統合テスト
- **必要なもの**: Docker + Testcontainers
- **実行時間**: 数秒〜十秒 (起動 cost)
- **対象**: SQL / migration / JSONB クエリ

### `platform-paper-plugin` (Lv3)

- **テスト種類**: smoke test のみ
- **必要なもの**: docker compose
- **実行時間**: 分単位
- **対象**: plugin が enable できるか、reload できるか

## CI で走らせるもの

```
./gradlew check
  ├── :plugin:domain:test            (純粋単位テスト)
  ├── :plugin:application:test       (Use case + fake)
  ├── :plugin:adapter:*:test         (各 adapter の単位/統合テスト)
  ├── :plugin:adapter:persistence-postgres:integrationTest  (Testcontainers)
  ├── :plugin:*:detekt
  ├── :plugin:*:konsistTest          (依存ルール)
  └── :plugin:platform-paper-plugin:smoke (docker compose, 1 jobs)
```

## カバレッジ目標 (Phase 1)

| 層 | line coverage |
|---|---|
| domain | 90%+ |
| application | 80%+ |
| adapter-minecraft-api (binding) | 70%+ |
| adapter-minecraft-impl-paper | 50% (Bukkit を mock しづらいため低め) |
| adapter-persistence-postgres | 70% (Testcontainers) |
| platform-paper-plugin | カバレッジ計測対象外 (smoke のみ) |

## 「やらない」テスト

- 実 Minecraft クライアントを起動して E2E
- 本番 cluster での自動テスト
- 試合の全シナリオを統合テスト

これらは手動 QA + canary deploy で担保する。
