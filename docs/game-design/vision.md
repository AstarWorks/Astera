# Vision

Astera は **AstarWorks 公式の Minecraft サーバープロジェクト**。

## North Star

> **「100% AI 開発」を実証する、コミュニティ駆動の攻城戦 & メタバース・プレイス**

Minecraft Java Edition を base に、以下を実現する:

1. **Siege Warfare PvP** — 攻城戦。非対称大規模 PvP。攻撃側がコアを破壊、防衛側が守る
2. **Community UGC** — ユーザーが武器・マップ・スキン・サウンドを投稿し、自動レビューを経て世界に追加できる
3. **Metaverse Place** — アリーナだけでなく、プレイヤーが恒久的に滞在・建築できる「場所」

これらを **コード・アセット・運用すべて AI 委託で**実現することが、Astera の独自性。

## 「100% AI 開発」が意味するもの

- **コード**: Claude Code Multi-Agent (subagent + skill) が生成・レビュー
- **アセット**: Phase 6 で texture / sound / 3D model / stage を AI 生成
- **運用**: incident-responder agent / release-noter agent / 翻訳 agent
- **判断**: 人間が「方向性」を決め、AI が「実装」を担う

ただし Phase 1〜5 では**生成 AI は使わない** ([[adr/0010]])。土台を固めてから解禁する。

## 関連する非ゴール

- **オリジナル MMO ではない** — Minecraft の上に乗る
- **アリーナ PvP だけではない** — Place / UGC で滞在型を目指す
- **完全閉鎖 OSS ではない** — public モノレポ + 私的 submodule で OSS を取り入れる

## 関連 doc

- [[pillars]]
- [[../roadmap]]
- [[../INDEX]]
