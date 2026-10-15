#!/usr/bin/env bash
# 本地操作演示主机（阿里云抢占式实例）的脚本，在 Git Bash 或任意 POSIX shell 里运行。
#   scripts/ecs.sh bootstrap        初始化一台新主机（主机被回收重建后先跑这个）
#   scripts/ecs.sh deploy [分支]    部署并验证（单元测试、静态检查、端到端测试），完成后自动拉回备份
#   scripts/ecs.sh backup           在主机上做数据快照并拉回本地 backups/ecs/
#   scripts/ecs.sh restore <文件>   把本地备份恢复到主机（新主机重建后用）
#   scripts/ecs.sh tunnel           建立 SSH 隧道，浏览器打开 http://localhost:5173
# 环境变量：EAB_ECS_HOST（默认 8.160.189.28）、EAB_ECS_KEY（默认 ~/.ssh/eab_ecs）
set -euo pipefail
HOST="${EAB_ECS_HOST:-8.160.189.28}"
KEY="${EAB_ECS_KEY:-$HOME/.ssh/eab_ecs}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BACKUP_DIR="$ROOT/backups/ecs"
SSH=(ssh -i "$KEY" -o BatchMode=yes -o ServerAliveInterval=30 "root@$HOST")
SCP=(scp -i "$KEY" -o BatchMode=yes -q)

backup() {
  mkdir -p "$BACKUP_DIR"
  local archive
  archive="$("${SSH[@]}" "sudo -u eab -H bash -lc 'cd /home/eab/app && pnpm -s data:backup'" | tail -1)"
  "${SCP[@]}" "root@$HOST:$archive" "$BACKUP_DIR/"
  echo "已拉回 $BACKUP_DIR/$(basename "$archive")"
}

case "${1:-}" in
  bootstrap)
    "${SCP[@]}" "$ROOT/deploy/bootstrap.sh" "root@$HOST:/root/bootstrap.sh"
    "${SSH[@]}" "bash /root/bootstrap.sh"
    ;;
  deploy)
    branch="${2:-main}"
    # 先切到目标分支，再运行该分支自带的部署脚本（主机上的旧代码可能还没有这个脚本）
    "${SSH[@]}" "sudo -u eab -H bash -lc 'cd /home/eab/app && git fetch -q --prune origin && git checkout -q $branch && git reset -q --hard origin/$branch'"
    "${SSH[@]}" "bash /home/eab/app/deploy/remote-deploy.sh $branch ${3:-}"
    backup
    ;;
  backup)
    backup
    ;;
  restore)
    file="${2:?用法：scripts/ecs.sh restore backups/ecs/eab-data-xxx.tar.gz}"
    "${SCP[@]}" "$file" "root@$HOST:/tmp/restore.tar.gz"
    "${SSH[@]}" "set -e; systemctl stop eab-demo || true
      cd /tmp && rm -rf eab-restore && mkdir eab-restore && tar -xzf restore.tar.gz -C eab-restore
      src=\$(ls -d /tmp/eab-restore/eab-data-*)
      install -d -o eab -g eab /home/eab/app/data
      cp -a \"\$src\"/. /home/eab/app/data/ && chown -R eab:eab /home/eab/app/data
      systemctl start eab-demo && echo 已恢复 \$(basename \"\$src\")"
    ;;
  tunnel)
    echo "隧道已建立：浏览器打开 http://localhost:5173 ，Ctrl+C 断开"
    ssh -i "$KEY" -N -L 5173:127.0.0.1:5173 -L 4000:127.0.0.1:4000 -L 4100:127.0.0.1:4100 "root@$HOST"
    ;;
  *)
    sed -n '2,9p' "$0"
    exit 2
    ;;
esac
