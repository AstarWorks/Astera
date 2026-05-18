---
status: Proposed
date: 2026-05-19
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0024: Provider 路線 — vanilla-first, optional provider adapters in Phase 4

## Status

Proposed (Wave _ops Phase C close — flip to Accepted on user approval before push). Supplements [[adr/0015-external-plugin-interrogation]] (per-plugin
verdicts) and [[adr/0022-multi-plugin-portable-architecture]] (ServiceRegistry
priority overlay pattern).

## Context

複数の過去設計案 (旧 polished-orbit roadmap / 旧 stage-based plan) で
`:adapter-providers` という Gradle モジュールが言及された — Oraxen /
MythicMobs / WeaponMechanics 等の外部 Paper plugin との接続を 1 モジュールに
集約するという発想。しかし、ADR-0022 multi-plugin refactor (2026-05-17) 完了
時点で **`:adapter-providers` モジュールは存在しない**。

```
$ grep -r 'adapter-providers' /IdeaProjects/Astera 2>/dev/null | wc -l
0
$ grep -r 'adapterProviders' /IdeaProjects/Astera 2>/dev/null | wc -l
0
```

代わりに採用されているパターン (ADR-0015 / ADR-0022 の積み上げ):

- 各機能 (item registry / projectile service / skill cast / item delivery 等) は
  **Astera 内製の port** で定義 (`IItemRegistry`, `IProjectileService`, ...)
- **Vanilla 既定実装** が `priority = 0` で `ServiceRegistry` に register
- 将来 provider plugin (Oraxen 等) が必要になったら、**optional adapter module**
  (e.g. `:plugin:provider-oraxen`) を新規 plugin として追加、その plugin が
  `onEnable` で `getPlugin("Oraxen")?.isEnabled` で soft-dep を確認、存在すれば
  `priority = 100` で port impl を register、欠落すれば何もしない (= Vanilla が
  そのまま居続ける)
- 全 provider 欠落でも Astera は boot green — ADR-0001 (Java-server-first) と
  ADR-0002 (vanilla-Paper-bootable) の延長

本 ADR の目的は 2 つ:

1. **(事後記録)** `:adapter-providers` モジュールが存在しないこと、その理由が
   意図的設計であることを公式記録に残す (再度「provider モジュール作るべき
   では」議論が起こったときの参照点)。
2. **(Phase 4 governance)** 将来 Oraxen / MythicMobs / WeaponMechanics 等の
   provider plugin を導入する際の契約 (port 配置 / soft-dep 検出タイミング /
   fallback 規約) を明文化。

## Decision

### 1. (事後記録) `:adapter-providers` を作らない

各 provider は **独立の Astera plugin** (`:plugin:provider-*`) として後段 Phase
で導入する。Core / 機能 plugin (`:plugin:weapons` 等) が provider plugin を
直接 import することは禁止 (`tools/architecture-test/.../ArchitectureTest.kt` の
sibling-isolation matrix で Konsist が enforce)。

### 2. (Phase 4 contract) Provider adapter plugin の作り方

provider plugin を新規追加する際の規約 (将来の実装者向け):

#### 2.1 配置

```
plugin/provider-<vendor>/
├── build.gradle.kts
├── src/main/kotlin/com/astarworks/astera/provider/<vendor>/
│   ├── adapter/                    ← <vendor>-Paper plugin との実 binding
│   │   └── <Vendor>ItemRegistry.kt
│   └── platform/                   ← JavaPlugin entrypoint
│       └── AsteraProvider<Vendor>Plugin.kt
└── src/main/resources/
    └── plugin.yml                  ← softdepend: [Astera-Core, <Vendor>]
```

provider plugin は `:plugin:core` に hard-depend (Core の port 型を使う) し、
対象 vendor plugin に **softdepend** する (`paper-plugin.yml` の `softdepend`)。
hard-depend してはいけない (= 外部 plugin 欠落で Astera 全体が落ちる事態を防ぐ)。

#### 2.2 onEnable プロトコル

```kotlin
class AsteraProviderOraxenPlugin : JavaPlugin() {
    override fun onEnable() {
        val core = (server.pluginManager.getPlugin("Astera-Core") as AsteraCorePlugin?)
            ?: run { logger.warning("Astera-Core not present — disabling."); return }

        val oraxen = server.pluginManager.getPlugin("Oraxen")
        if (oraxen == null || !oraxen.isEnabled) {
            logger.info("Oraxen not present/enabled — vanilla IItemRegistry stays.")
            return
        }

        // Confirm Oraxen version is in supported range (compile-time SemVer check).
        val version = oraxen.description.version
        if (!OraxenItemRegistry.supports(version)) {
            logger.warning("Oraxen $version not supported by Astera-Provider-Oraxen — vanilla stays.")
            return
        }

        // Register at priority 100 — overrides Core's vanilla impl (priority 0).
        core.services.register<IItemRegistry<IMcItemTemplate>>(
            OraxenItemRegistry(oraxen),
            priority = 100,
        )
        logger.info("OraxenItemRegistry registered at priority=100.")
    }
}
```

要点:

- **soft-dep 検出は onEnable 1 度のみ** — 動的 plugin reload は想定外
  (`/reload` 後の挙動は best-effort)
- **欠落時は logger.info で済ます** — warning でも error でもない (vanilla
  fallback は仕様内挙動)
- **version 範囲チェック** — `OraxenItemRegistry.supports(version: String): Boolean`
  という static method を provider adapter 内に持たせ、breaking change が起きた
  vendor version を `false` で弾く。compile 通っても run-time に弾く責務。

#### 2.3 Fallback chain

`ServiceRegistry.get<X>()` は priority 最大の impl を返す。同 priority が複数の
ときは登録順 (Paper の plugin load order 依存)。Astera の規約:

- Vanilla 既定実装: `priority = 0`
- Provider adapter: `priority = 100`
- Postgres / 外部 storage 上書き (Persistence plugin): `priority = 100`
  (provider と同 priority だが、port 別なので衝突しない)

provider と Postgres は **異なる port** に対するので衝突しない設計。同じ port
を複数 provider が override したい場合 (例: Oraxen と ItemAdder の両方が
`IItemRegistry` を実装したい) は **新規 ADR が必要** (priority だけでは決まらず、
明示的な優先順位ルールを ADR で定義する)。

#### 2.4 boot green 保証

Konsist の既存 rule (#11 "Feature plugins must not import Bukkit family from
domain / application") に加え、**provider plugin の不在で Astera が boot
green** であることを定期的に手動検証する。kind cluster の `make local-e2e`
スモーク試験 (Wave _ops で integrated) に「provider plugin を全部
removed した状態で Paper が起動する」ことを assert する step を Phase 4 で追加
する。

#### 2.5 ADR allocation

provider 採用ごとに個別 ADR:

- Oraxen 採用 → ADR-XXXX (Phase 4)
- MythicMobs 採用 → ADR-XXXX (Phase 5)
- WeaponMechanics 採用 → 採用しない方針が ADR-0015 で確定済 (Decline)

本 ADR-0024 は **provider plugin の作り方** を定義するメタ-ADR であり、
個別 provider 採否の決定は別 ADR に委任する。

## Consequences

### Positive

- `:adapter-providers` という 1 巨大モジュールを作らずに済む — port 別に小さい
  plugin に切り分かれ、コンパイル時間 / shadow jar size / 単一障害点が分散
- Vanilla パスが常に first-class — 開発者ローカル (kind cluster や docker-compose)
  で外部 plugin なしに動くので試験コストが低い (CI 不要)
- Sibling-isolation の Konsist rule (ADR-0022) がそのまま provider plugin にも
  適用される — `:plugin:weapons` は `:plugin:provider-oraxen` を直接 import
  できず、必ず Core の port 経由でしか触れない

### Negative / Trade-offs

- provider plugin ごとに `paper-plugin.yml` / `build.gradle.kts` / platform
  module を準備するので boilerplate が増える — 4-5 個になったらテンプレ化を
  検討
- provider plugin の version 互換性は provider 側の release pace 依存 — vendor
  が breaking change を出したら provider plugin 側で吸収するか、`supports()`
  range を縮める対応が要る
- Vanilla 既定実装が常に存在することは「provider なしでも fully functional」
  を強制する — provider にしか提供できない高度な機能 (例: Oraxen の `customModelData`
  自動採番) は Astera 内で別代替を用意するか、その機能を offer しない判断を
  port 単位で行う

### Mitigations

- provider plugin template: 4 個目あたりで Cookiecutter / 自作 skill で
  `plugin/provider-<name>/` 雛形を生成する (deferred to Phase 4)
- vendor version pinning: `gradle/libs.versions.toml` の sentinel
  (`oraxen = "999.999.999"`) と provider plugin の `supports()` range を同期、
  Astera リリース時に compile-time check を CI に組み込む
- 機能差分の文書化: 各 provider plugin が `docs/providers/<name>.md` に「Vanilla
  と比べて何が増えるか」を記述、開発者が選択コストを理解できる

## Alternatives Considered

- **`:adapter-providers` 単一モジュール**: 1 モジュールに Oraxen / MythicMobs /
  WeaponMechanics 全部を相乗り。理由 (却下):
  - vendor の 1 つでも soft-dep 検出に失敗するとモジュール全体が試験不能になる
  - shadow jar が肥大化、軽量 deployment の妨げ
  - ADR-0022 の sibling-isolation 哲学 (機能粒度 = plugin 粒度) に反する
- **Provider を `:plugin:core` 内に直接 import**: 「優しさを取って Core 内で
  `Bukkit.getPluginManager().getPlugin("Oraxen")` を直書き」案。理由 (却下):
  - Core が vendor-neutral である ADR-0002 違反 (Bukkit-free + provider-free
    である Core が provider check するのは layer 矛盾)
  - Konsist rule "core.adapter.api.* must not import Bukkit family" を緩和
    する必要が出る
- **Provider に hard-depend (paper-plugin.yml `depend`)**: 「vendor が前提なら
  落ちて良い」案。理由 (却下): ADR-0001/0002 の「vanilla Paper で boot green」
  違反

## References

- 関連 ADR:
  - [[adr/0015-external-plugin-interrogation]] — per-plugin Adopt/Compare/Decline
    判定
  - [[adr/0022-multi-plugin-portable-architecture]] — ServiceRegistry priority
    pattern
  - [[adr/0001-java-server-first]] / [[adr/0002-paper-edge-only]] — vanilla
    boot 要件
  - [[adr/0029-rtm-empirical-catalog]] §"External plugin strategies" — RTM
    11 plugin の Adopt/Decline 判断履歴
- 関連 docs:
  - `tools/architecture-test/.../ArchitectureTest.kt` — sibling-isolation Konsist
  - `.claude/rules/module-layout.md` — 7-plugin layout (provider 追加時にこの
    matrix を拡張する)
- 外部リンク:
  - [PaperMC plugin.yml softdepend semantics](https://docs.papermc.io/paper/dev/plugin-yml#softdepend)
