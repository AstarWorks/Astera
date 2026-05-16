# Getting Started

Astera にコントリビュートするための local 環境構築手順。

## 想定読者

- Astera に武器・スキル・マップを追加したい人
- バグ修正 PR を出したい人
- 新機能を提案したい人

## 1. 前提

- Git
- Docker + Docker Compose
- (Kotlin 開発する場合のみ) JDK 25 + IntelliJ IDEA Community/Ultimate

YAML だけで武器を追加するなら **Docker さえあれば十分**。

## 2. リポジトリ取得

```bash
git clone https://github.com/AstarWorks/Astera.git
cd Astera
git submodule update --init --recursive   # private/ は権限がある人のみ
```

`private/` submodule にアクセスできない場合は、public 部分のみで動く構成が用意されています。

## 3. ローカル起動 (Docker Compose)

```bash
docker compose up -d
```

これで以下が立ち上がります:
- Paper 26.x サーバー (Astera プラグイン付き) — port 25565
- PostgreSQL — port 5432
- Redis — port 6379

Minecraft クライアント (Java Edition 26.1.x) で `localhost` に接続できれば成功です。

## 4. 武器を追加してみる

[`add-a-weapon-yaml.md`](add-a-weapon-yaml.md) を参照。`content/weapons/` に YAML を 1 枚置くだけ。

## 5. (Kotlin 開発の場合) ビルド

```bash
./gradlew build
```

これで:
- 全モジュールのコンパイル
- Detekt + Konsist (依存ルール) チェック
- 単位テスト

が走ります。失敗したら PR は通りません。

```bash
./gradlew :plugin:platform-paper-plugin:shadowJar
```

成果物は `plugin/platform-paper-plugin/build/libs/astera-paper-*.jar` に出力されます。これを上記 docker compose の `plugins/` にマウントすると最新ビルドで試せます。

## 6. PR を出す

[`commit-and-pr.md`](commit-and-pr.md) を参照 (TBD)。

## 困ったら

- Discord (TBD): `#contributors` チャンネル
- GitHub Issue: バグ・機能リクエスト
- AI レビュー: PR を出すと自動で AI レビュー bot がコメント
