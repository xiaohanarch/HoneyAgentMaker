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
# 新主机开机后 unattended-upgrades 会占用 dpkg 锁一段时间，装系统依赖前先等它释放
wait_for_apt() {
  for _ in $(seq 1 180); do
    fuser /var/lib/dpkg/lock-frontend /var/lib/dpkg/lock >/dev/null 2>&1 || return 0
    sleep 5
  done
  echo "等待 dpkg 锁超时" >&2
  return 1
}

log "拉取 $BRANCH"
as_eab "git fetch --prune origin && git checkout -q $BRANCH && git reset -q --hard origin/$BRANCH"
log "当前提交：$(as_eab 'git log --oneline -1')"

log "安装依赖"
as_eab "pnpm install --frozen-lockfile --reporter=silent"

log "单元测试、静态检查、资产校验"
as_eab "pnpm exec vitest run --reporter=dot"
as_eab "pnpm lint"
as_eab "pnpm typecheck"
as_eab "pnpm assets:validate"

log "Java 后端构建与测试（Maven 镜像在 /home/eab/.m2/settings.xml）"
as_eab "pnpm -s test:java"

restart_service() {
  systemctl restart eab-demo
  for i in $(seq 1 60); do
    curl -fsS http://127.0.0.1:4000/health >/dev/null 2>&1 \
      && curl -fsS http://127.0.0.1:4100/health >/dev/null 2>&1 \
      && curl -fsS http://127.0.0.1:5173/ >/dev/null 2>&1 && break
    sleep 2
  done
  curl -fsS http://127.0.0.1:4000/health && echo
}

# 真实模型配置只在主机上，不进仓库。端到端测试按脚本化模型的确定输出断言，测试期间暂时移开，结束后一定恢复
MODEL_ENV=/home/eab/.config/eab/model.env
restore_model_env() {
  if [ -f "$MODEL_ENV.paused" ]; then
    mv "$MODEL_ENV.paused" "$MODEL_ENV"
    log "已恢复真实模型配置，重启服务"
    restart_service
  fi
}
trap restore_model_env EXIT

log "重启服务"
install -m 644 "$APP/deploy/eab-demo.service" /etc/systemd/system/eab-demo.service
systemctl daemon-reload
systemctl enable -q eab-demo
if [ "$SKIP_E2E" != "--skip-e2e" ] && [ -f "$MODEL_ENV" ]; then
  log "端到端测试期间暂时改用脚本化模型"
  mv "$MODEL_ENV" "$MODEL_ENV.paused"
fi
restart_service

if [ "$SKIP_E2E" != "--skip-e2e" ]; then
  log "端到端测试（复用正在运行的演示环境）"
  if [ ! -d /home/eab/.cache/ms-playwright ]; then
    log "安装 Chromium 及其系统依赖（首次）"
    wait_for_apt
    # 系统依赖需要 root，用仓库里已安装的 playwright，不另外下载
    (cd "$APP" && ./node_modules/.bin/playwright install-deps chromium >/dev/null)
    as_eab "PLAYWRIGHT_DOWNLOAD_HOST=https://npmmirror.com/mirrors/playwright pnpm exec playwright install chromium"
  fi
  as_eab "pnpm test:e2e --reporter=line"
fi
restore_model_env

log "数据快照"
as_eab "pnpm -s data:backup"
log "部署完成"
