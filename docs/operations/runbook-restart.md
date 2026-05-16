# Runbook: Minecraft サーバー再起動

## 自動再起動

`deploy/minecraft/jobs/restart-cronjob.yaml` が毎日 **4:00 JST** に Lobby と Game を RCON 経由で停止する。Pod は Kubernetes が自動再起動する (Deployment / strategy: Recreate)。

Re-DIVERSE の方式を踏襲: 通知 → save-all → 10 秒待機 → stop。

## 手動再起動

### 1 サーバーだけ再起動

RCON CLI コンテナを起動:

```bash
kubectl run rcon-tmp -n astera --rm -it --restart=Never \
  --image=itzg/rcon-cli:latest \
  -- /bin/sh

# RCON password を取得
kubectl get secret minecraft-rcon -n astera -o jsonpath='{.data.lobby-password}' | base64 -d

# Lobby 停止
rcon-cli --host minecraft-lobby.astera.svc --port 25575 --password <pw> \
  "say [Maintenance] Restarting in 10s..."
rcon-cli --host minecraft-lobby.astera.svc --port 25575 --password <pw> save-all flush
sleep 10
rcon-cli --host minecraft-lobby.astera.svc --port 25575 --password <pw> stop
```

Pod が再起動するまで 30〜60 秒。

### Pod 強制再起動 (RCON が応答しない時)

```bash
kubectl rollout restart deployment/minecraft-lobby -n astera
```

これは **save をスキップする**ので、書き込み中の world データが破損する可能性あり。最終手段。

### Velocity Proxy だけ再起動

```bash
kubectl rollout restart deployment/velocity-proxy -n astera
```

接続中の player は kick されるが、自動再接続でクライアント側はほぼ気付かない (実装次第)。

## 確認

```bash
kubectl get pods -n astera
kubectl logs -n astera deployment/minecraft-lobby --tail=50
kubectl logs -n astera deployment/minecraft-lobby -f
```

## 注意

- **Production で stop と同時に rollout restart を打たない**: 二重停止で health check が連続失敗するとアラート発火する
- 大型イベント前後は手動で予告 + maintenance window を取る
- RCON パスワードはこの runbook に書かない (Secret から都度取る)
