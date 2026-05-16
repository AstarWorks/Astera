# Glossary

Astera 内で使われる用語。チームと AI agent の共通語彙。

## ゲーム概念

| 用語 | 意味 |
|---|---|
| **Siege** | 攻城戦。Astera の中核となる非対称大規模 PvP。攻撃側がコアを破壊、防衛側が守る |
| **Place** | 常設ワールド。アリーナではなく、プレイヤーが恒久的に滞在・建築できる空間 |
| **UGC** | User-Generated Content。武器・マップ・スキン・サウンドなど、ユーザーが作って投稿するコンテンツ |
| **Match** | 1 試合の単位。Siege やその他ゲームモードのインスタンス |
| **Loadout** | プレイヤーが Match に持ち込む武器・防具・スキルの組み合わせ |
| **Stage** | Match が行われる物理的なマップ |
| **Plot** | Place 内でプレイヤーが所有する区画 |
| **Core** | Siege で攻撃対象になる構造物 |
| **Star** | ゲーム内通貨/リソースの 1 つ。マップに配置された Generator から定期生成 |
| **Generator** | Star などを定期的に発生させる装置 (Hypixel BedWars 的) |
| **Anomaly** | Match 中にランダムで発生するイベント (地形変化・ステータス変化) |
| **Norma** | レベルアップのためのミッション/ノルマ |

## アーキテクチャ概念

| 用語 | 意味 |
|---|---|
| **Domain** | Lv0。純粋なゲームロジック。Minecraft 非依存 |
| **Application** | Lv1。Use case と port (interface) を定義する層 |
| **Adapter** | Lv2。Port を実装し、外部 I/O (DB・MC・Discord) を扱う層 |
| **Platform** | Lv3。Entry point。JavaPlugin や Mod 本体 |
| **Port** | Application が定義する interface (inbound / outbound) |
| **Inbound Port** | 外部から application を呼び出す窓口 |
| **Outbound Port** | Application が外部に出すための窓口 |
| **MC-API (vendor-neutral)** | Bukkit/Paper を直接触らずに Minecraft 概念を扱う中間抽象層 |
| **Server Core** | Paper / Spigot / Folia / Purpur / Velocity などのサーバー実装 |
| **Provider** | 外部 Paper プラグイン (Oraxen 等) を差し替え可能にするパターン |
| **DIP** | Dependency Inversion Principle。SOLID の D |

## 運用概念

| 用語 | 意味 |
|---|---|
| **GitOps** | Git をクラスタの正本にして ArgoCD が同期する運用 |
| **App-of-Apps** | ArgoCD の Application が他の Application を管理するパターン |
| **Kustomize** | YAML 重ね合わせによる環境差分管理 |
| **Velocity** | Minecraft の Proxy サーバー (BungeeCord 後継) |
| **Lobby** | プレイヤーの最初の接続先。Velocity が振り分ける |
| **Aikar's Flags** | PaperMC コミュニティ標準の JVM チューニング flag セット |
| **RCON** | Minecraft サーバーへのリモートコマンド実行プロトコル |
| **PV / PVC** | Persistent Volume / Persistent Volume Claim (k8s 永続ストレージ) |

## AI 委託概念

| 用語 | 意味 |
|---|---|
| **Subagent** | Claude Code の専門化された agent (weapon-designer, translator など) |
| **Skill** | `/new-weapon` のような slash command として呼び出せる手順 |
| **Delegation Line** | 人間レビューが必須になるかどうかの境界 |
