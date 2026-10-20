#!/usr/bin/env bash
# 本地操作演示主机（阿里云抢占式实例）的脚本，在 Git Bash 或任意 POSIX shell 里运行。
#   scripts/ecs.sh bootstrap        初始化一台新主机（主机被回收重建后先跑这个）
#   scripts/ecs.sh deploy [分支]    部署并验证（单元测试、静态检查、端到端测试），完成后自动拉回备份
#   scripts/ecs.sh backup           在主机上做数据快照并拉回本地 backups/ecs/
#   scripts/ecs.sh restore <文件>   把本地备份恢复到主机（新主机重建后用）
#   scripts/ecs.sh tunnel           建立 SSH 隧道，浏览器打开 http://localhost:5173
#   scripts/ecs.sh auth-init        为全部演示身份生成随机口令，写本地与主机的 access.env 并重启生效
#   scripts/ecs.sh auth on|off      临时开关口令门禁（跑验收脚本、走查时用），改完自动重启
#   scripts/ecs.sh open             打印公网地址（https，自签证书）与口令的查看方式
#   scripts/ecs.sh ca               拉取 Caddy 根证书到本地，装到设备后访问不再有证书警告
# 环境变量：EAB_ECS_HOST（默认 8.160.189.28）、EAB_ECS_KEY（默认 ~/.ssh/eab_ecs）
set -euo pipefail
HOST="${EAB_ECS_HOST:-8.160.189.28}"
KEY="${EAB_ECS_KEY:-$HOME/.ssh/eab_ecs}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BACKUP_DIR="$ROOT/backups/ecs"
SSH=(ssh -i "$KEY" -o BatchMode=yes -o ServerAliveInterval=30 "root@$HOST")
SCP=(scp -i "$KEY" -o BatchMode=yes -q)
ACCESS_ENV_HOST=/home/eab/.config/eab/access.env
ACCESS_ENV_LOCAL="$HOME/.config/eab/access.env"
# DemoDirectory 的全部演示身份（backend/eab-directory），auth-init 逐个生成口令
DEMO_USERS=(zhangmin liqiang wangfang sunkg chengong zhaoadmin zhouting vendordev platformops)

backup() {
  mkdir -p "$BACKUP_DIR"
  local archive
  archive="$("${SSH[@]}" "sudo -u eab -H bash -lc 'cd /home/eab/app && pnpm -s data:backup'" | tail -1)"
  "${SCP[@]}" "root@$HOST:$archive" "$BACKUP_DIR/"
  echo "已拉回 $BACKUP_DIR/$(basename "$archive")"
}

# 重启 eab-demo 并等待三个核心服务健康
restart_and_wait() {
  "${SSH[@]}" "systemctl restart eab-demo
    for i in \$(seq 1 60); do
      curl -fsS http://127.0.0.1:4000/health >/dev/null 2>&1 \
        && curl -fsS http://127.0.0.1:4100/health >/dev/null 2>&1 \
        && curl -fsS http://127.0.0.1:5173/ >/dev/null 2>&1 && break
      sleep 2
    done
    curl -fsS http://127.0.0.1:4000/health >/dev/null && echo 服务已重启"
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
  auth-init)
    if [ -f "$ACCESS_ENV_LOCAL" ]; then
      # 主机重建后重推即可，重新生成会让设备上已保存的口令全部失效
      echo "本地已有 $ACCESS_ENV_LOCAL，沿用现有口令推送到主机（重新生成请先删除它）"
    else
      passcodes=""
      for user in "${DEMO_USERS[@]}"; do
        # 不能用「tr </dev/urandom | head」：head 读满退出会让 tr 收到 SIGPIPE，set -o pipefail 下整个脚本被杀
        pass="$(openssl rand -hex 8)"
        passcodes="${passcodes:+$passcodes,}$user:$pass"
      done
      mkdir -p "$HOME/.config/eab"
      umask 077
      cat > "$ACCESS_ENV_LOCAL" <<EOF
# 公网登录口令（scripts/ecs.sh auth-init 生成，只保存在本机与演示主机，不进仓库与快照）
EAB_DEMO_PASSCODES=$passcodes
# Caddy 反代下按 X-Forwarded-For 还原真实客户端地址，登录限流按真实 IP 计数
EAB_FORWARD_HEADERS_STRATEGY=native
EAB_LOGIN_RATE_PER_MINUTE=20
EOF
    fi
    "${SCP[@]}" "$ACCESS_ENV_LOCAL" "root@$HOST:/tmp/access.env"
    "${SSH[@]}" "install -d -o eab -g eab /home/eab/.config/eab \
      && install -o eab -g eab -m 600 /tmp/access.env $ACCESS_ENV_HOST && rm /tmp/access.env \
      && echo 口令配置已写入主机"
    restart_and_wait
    echo "口令已写入 $ACCESS_ENV_LOCAL（权限 600），主机端同步生效"
    echo "查看口令：cat \"$ACCESS_ENV_LOCAL\""
    echo "临时关闭（跑验收脚本时）：scripts/ecs.sh auth off"
    ;;
  auth)
    case "${2:-}" in
      on)
        "${SSH[@]}" "if [ -f $ACCESS_ENV_HOST.paused ]; then mv $ACCESS_ENV_HOST.paused $ACCESS_ENV_HOST; fi"
        restart_and_wait
        echo "口令门禁：开"
        ;;
      off)
        "${SSH[@]}" "if [ -f $ACCESS_ENV_HOST ]; then mv $ACCESS_ENV_HOST $ACCESS_ENV_HOST.paused; fi"
        restart_and_wait
        echo "口令门禁：关（跑完验收脚本记得 auth on 恢复）"
        ;;
      *)
        echo "用法：scripts/ecs.sh auth on|off" >&2
        exit 2
        ;;
    esac
    ;;
  open)
    echo "公网地址：https://$HOST （自签证书，首次访问在浏览器确认一次）"
    echo "口令查看：cat \"$ACCESS_ENV_LOCAL\" （未生成时先运行 scripts/ecs.sh auth-init）"
    echo "备选方式：scripts/ecs.sh tunnel 后打开 http://localhost:5173"
    ;;
  ca)
    mkdir -p "$HOME/.config/eab"
    "${SCP[@]}" "root@$HOST:/var/lib/caddy/pki/authorities/local/root.crt" "$HOME/.config/eab/caddy-root.crt"
    echo "已保存到 $HOME/.config/eab/caddy-root.crt，安装到自己的设备后访问不再有证书警告："
    echo "  Windows：双击证书 -> 安装证书 -> 本地计算机 -> 受信任的根证书颁发机构"
    echo "  macOS：钥匙串访问 -> 系统 -> 导入，然后信任"
    echo "  Linux：sudo cp 到 /usr/local/share/ca-certificates/ 后 update-ca-certificates"
    ;;
  *)
    sed -n '2,14p' "$0"
    exit 2
    ;;
esac
