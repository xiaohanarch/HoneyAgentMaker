#!/usr/bin/env bash
# ERP Agent Builder 演示主机初始化（Ubuntu 24.04，root 执行，可重复执行）
set -euo pipefail
NODE_VERSION=24.14.1
MIRROR=https://registry.npmmirror.com
log() { echo "[$(date +%T)] $*"; }

log "安装系统依赖"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq git curl xz-utils ca-certificates python3-venv openjdk-21-jdk-headless >/dev/null
log "$(java -version 2>&1 | head -1)"

if ! node -v 2>/dev/null | grep -q "v${NODE_VERSION}"; then
  log "安装 Node ${NODE_VERSION}（npmmirror）"
  curl -fsSL "https://npmmirror.com/mirrors/node/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-x64.tar.xz" | tar -xJ -C /opt
  ln -sfn "/opt/node-v${NODE_VERSION}-linux-x64" /opt/node
  for bin in node npm npx corepack; do ln -sf "/opt/node/bin/${bin}" "/usr/local/bin/${bin}"; done
fi
log "Node $(node -v)"

npm config set registry "$MIRROR" --location=global
if ! pnpm -v 2>/dev/null | grep -q '^9\.15\.0$'; then
  log "安装 pnpm 9.15.0"
  npm install -g pnpm@9.15.0 >/dev/null
  ln -sf /opt/node/bin/pnpm /usr/local/bin/pnpm
fi
log "pnpm $(pnpm -v)"

if ! id eab >/dev/null 2>&1; then
  log "创建运行用户 eab"
  useradd --create-home --shell /bin/bash eab
fi
# 以 eab 身份在其主目录下执行，避免继承 root 的工作目录导致权限错误
as_eab() { sudo -u eab -H bash -lc "cd ~ && $*"; }
as_eab "pnpm config set registry $MIRROR >/dev/null"
# Maven Wrapper 下载 Maven 与依赖时走阿里云镜像；只写在主机上，不进仓库（ADR 0006）
as_eab "mkdir -p ~/.m2 && [ -f ~/.m2/settings.xml ] || printf '%s' '<settings><mirrors><mirror><id>aliyun</id><mirrorOf>central</mirrorOf><url>https://maven.aliyun.com/repository/public</url></mirror></mirrors></settings>' > ~/.m2/settings.xml"
as_eab "grep -q MVNW_REPOURL ~/.profile || echo 'export MVNW_REPOURL=https://maven.aliyun.com/repository/public' >> ~/.profile"

# 外部 Agent 样例（P7）：供应商风险舆情 Agent 用 Python 3.12 + uv，pnpm demo 启动时自动 uv sync；
# uv 装在 eab 用户的 ~/.local/bin；Python 依赖按 uv.lock 安装
if ! as_eab "command -v uv >/dev/null || [ -x ~/.local/bin/uv ]"; then
  log "安装 uv（pip install --user，清华镜像）"
  apt-get install -y -qq python3-pip >/dev/null
  as_eab "python3 -m pip install --user --break-system-packages -q -i https://pypi.tuna.tsinghua.edu.cn/simple uv"
fi
log "uv $(as_eab '~/.local/bin/uv --version' || echo 未安装)"

log "拉取代码"
if [ ! -d /home/eab/app/.git ]; then
  as_eab "git clone https://github.com/xiaohanarch/HoneyAgentMaker.git /home/eab/app"
fi
log "完成：$(as_eab 'git -C /home/eab/app log --oneline -1')"
log "部署代码与服务请在本地运行 scripts/ecs.sh deploy"
