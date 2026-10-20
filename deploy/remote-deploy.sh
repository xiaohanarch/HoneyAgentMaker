#!/usr/bin/env bash
# 在演示主机上以 root 执行：拉取指定分支、安装依赖、跑测试、重启服务、做一次数据快照。
#   bash /home/eab/app/deploy/remote-deploy.sh [分支，默认 main] [--skip-e2e]
# 主机是抢占式实例，随时可能被回收；数据快照由本地的 scripts/ecs.sh backup 拉回。
# 公网入口（Caddy，443）随部署安装：安全组放行 443 后经 https 访问，登录由 access.env 的口令门禁把关。
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

# 真实模型配置与公网口令只在主机上，不进仓库。端到端测试按脚本化模型、无口令的确定行为断言，
# 测试期间暂时移开，结束后一定恢复
MODEL_ENV=/home/eab/.config/eab/model.env
ACCESS_ENV=/home/eab/.config/eab/access.env
restore_envs() {
  local restored=0
  if [ -f "$MODEL_ENV.paused" ]; then mv "$MODEL_ENV.paused" "$MODEL_ENV"; restored=1; fi
  if [ -f "$ACCESS_ENV.paused" ]; then mv "$ACCESS_ENV.paused" "$ACCESS_ENV"; restored=1; fi
  if [ "$restored" = 1 ]; then
    log "已恢复主机本地的模型与口令配置，重启服务"
    restart_service
  fi
}
trap restore_envs EXIT

# 抢占式实例的公网 IP 会变：以阿里云元数据为准，取不到时回落到固定地址
public_ip() {
  curl -fsS --max-time 5 http://100.100.100.200/latest/meta-data/public-ipv4 || echo 8.160.189.28
}

install_caddy() {
  if [ ! -x /usr/local/bin/caddy ]; then
    log "安装 Caddy 静态二进制（校验 sha256）"
    local version=2.11.7
    local sum=727b91701a392de6ebc5027509f548bf39979e5216340d0faed8fa5e69c84f8b
    curl -fsSL "https://github.com/caddyserver/caddy/releases/download/v${version}/caddy_${version}_linux_amd64.tar.gz" \
      -o /tmp/caddy.tgz
    echo "${sum}  /tmp/caddy.tgz" | sha256sum -c -
    tar -xzf /tmp/caddy.tgz -C /usr/local/bin caddy
    rm /tmp/caddy.tgz
    getent group caddy >/dev/null || groupadd --system caddy
    getent passwd caddy >/dev/null || useradd --system --gid caddy --home-dir /var/lib/caddy --shell /usr/sbin/nologin caddy
  fi
  install -d -o caddy -g caddy /etc/caddy /var/lib/caddy
  sed "s/{{PUBLIC_IP}}/$PUBLIC_IP/g" "$APP/deploy/Caddyfile" > /etc/caddy/Caddyfile
  install -m 644 "$APP/deploy/caddy.service" /etc/systemd/system/caddy.service
  systemctl daemon-reload
  systemctl enable -q caddy
  systemctl restart caddy
}

log "安装 Caddy 公网入口（https://$(public_ip)）"
PUBLIC_IP="$(public_ip)"
install_caddy

log "重启服务"
install -m 644 "$APP/deploy/eab-demo.service" /etc/systemd/system/eab-demo.service
systemctl daemon-reload
systemctl enable -q eab-demo
if [ "$SKIP_E2E" != "--skip-e2e" ]; then
  if [ -f "$MODEL_ENV" ]; then
    log "端到端测试期间暂时改用脚本化模型"
    mv "$MODEL_ENV" "$MODEL_ENV.paused"
  fi
  if [ -f "$ACCESS_ENV" ]; then
    log "端到端测试期间暂时关闭口令门禁"
    mv "$ACCESS_ENV" "$ACCESS_ENV.paused"
  fi
fi
restart_service

if [ "$SKIP_E2E" != "--skip-e2e" ]; then
  # e2e 的故事 A/B 会发布租户 Agent 与扩展，在遗留数据上跑会因版本已发布而误报失败，先重置演示状态
  as_eab "pnpm -s seed:reset"
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
restore_envs

log "验证公网入口与口令门禁"
# 证书为公网 IP 签发，本地探活用 --resolve 把 SNI 指到公网地址、连接留在回环
https_code="$(curl -sk --resolve "$PUBLIC_IP:443:127.0.0.1" -o /dev/null -w '%{http_code}' "https://$PUBLIC_IP/")"
if [ "$https_code" != 200 ]; then
  echo "经 Caddy 的 https 访问返回 $https_code" >&2
  exit 1
fi
if [ -f "$ACCESS_ENV" ]; then
  passcodes="$(sed -n 's/^EAB_DEMO_PASSCODES=//p' "$ACCESS_ENV")"
  first_pair="${passcodes%%,*}"
  probe_user="${first_pair%%:*}"
  probe_pass="${first_pair#*:}"
  no_pass="$(curl -s -o /dev/null -w '%{http_code}' -X POST http://127.0.0.1:4000/identity/v1/login \
    -H 'content-type: application/json' -d "{\"userId\":\"$probe_user\"}")"
  bad_pass="$(curl -s -o /dev/null -w '%{http_code}' -X POST http://127.0.0.1:4000/identity/v1/login \
    -H 'content-type: application/json' -d "{\"userId\":\"$probe_user\",\"passcode\":\"wrong-passcode\"}")"
  good_pass="$(curl -s -o /dev/null -w '%{http_code}' -X POST http://127.0.0.1:4000/identity/v1/login \
    -H 'content-type: application/json' -d "{\"userId\":\"$probe_user\",\"passcode\":\"$probe_pass\"}")"
  if [ "$no_pass" != 401 ] || [ "$bad_pass" != 401 ] || [ "$good_pass" != 200 ]; then
    echo "口令门禁异常：无口令=$no_pass 错口令=$bad_pass 正确口令=$good_pass" >&2
    exit 1
  fi
  log "口令门禁生效：无口令与错误口令返回 401，正确口令返回 200"
else
  log "警告：未配置 /home/eab/.config/eab/access.env，公网将以无口令模式运行；先在本地运行 scripts/ecs.sh auth-init"
fi

log "数据快照"
as_eab "pnpm -s data:backup"
log "部署完成"
