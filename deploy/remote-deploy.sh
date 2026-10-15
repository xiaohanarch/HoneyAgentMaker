#!/usr/bin/env bash
# 在演示主机上以 root 执行：拉取指定分支、安装依赖、跑测试、重启服务、做一次数据快照。
#   bash /home/eab/app/deploy/remote-deploy.sh [分支，默认 main] [--skip-e2e]
# 主机是抢占式实例，随时可能被回收；数据快照由本地的 scripts/ecs.sh backup 拉回。
set -euo pipefail
BRANCH="${1:-main}"
SKIP_E2E="${2:-}"
APP=/home/eab/app
as_eab() { sudo -u eab -H bash -lc "cd $APP && $*"; }
log() { echo "[$(date +%T)] $*"; }

log "拉取 $BRANCH"
as_eab "git fetch --prune origin && git checkout -q $BRANCH && git reset -q --hard origin/$BRANCH"
log "当前提交：$(as_eab 'git log --oneline -1')"

log "安装依赖"
as_eab "pnpm install --frozen-lockfile --reporter=silent"

log "单元测试、静态检查、资产校验"
as_eab "pnpm test --reporter=dot"
as_eab "pnpm lint"
as_eab "pnpm typecheck"
as_eab "pnpm assets:validate"

log "重启服务"
install -m 644 "$APP/deploy/eab-demo.service" /etc/systemd/system/eab-demo.service
systemctl daemon-reload
systemctl enable -q eab-demo
systemctl restart eab-demo
for i in $(seq 1 60); do
  curl -fsS http://127.0.0.1:4000/health >/dev/null 2>&1 \
    && curl -fsS http://127.0.0.1:4100/health >/dev/null 2>&1 \
    && curl -fsS http://127.0.0.1:5173/ >/dev/null 2>&1 && break
  sleep 2
done
curl -fsS http://127.0.0.1:4000/health && echo

if [ "$SKIP_E2E" != "--skip-e2e" ]; then
  log "端到端测试（复用正在运行的演示环境）"
  if [ ! -d /home/eab/.cache/ms-playwright ]; then
    PLAYWRIGHT_DOWNLOAD_HOST=https://npmmirror.com/mirrors/playwright \
      bash -lc "cd $APP && npx --yes playwright@1.64.1 install-deps chromium" >/dev/null
    as_eab "PLAYWRIGHT_DOWNLOAD_HOST=https://npmmirror.com/mirrors/playwright pnpm exec playwright install chromium"
  fi
  as_eab "pnpm test:e2e --reporter=line"
fi

log "数据快照"
as_eab "pnpm -s data:backup"
log "部署完成"
