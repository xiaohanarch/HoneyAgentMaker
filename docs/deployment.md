# 演示主机部署

演示系统部署在一台阿里云 ECS 上做验证和测试（Ubuntu 24.04，4 核 16G）。这台主机是抢占式实例，云厂商可能随时回收，所以部署按“主机可丢弃”设计：代码以 Git 仓库为准，主机初始化和部署全部由仓库里的脚本完成，运行数据定期做快照并拉回本地。主机上不保存任何只此一份的东西。

## 访问方式

浏览器直接打开 `https://<主机公网IP>`（默认 8.160.189.28）。公网入口由主机上的 Caddy 承担：它以自签证书（`tls internal`，为公网 IP 签发并自动续期）监听 443，反代到本机的 vite；六个服务本身仍只监听 127.0.0.1，公网实际开放的就是 443 一个端口（80 只做跳转到 https，嫌多可以不开）。首次访问浏览器会提示证书不受信任，确认一次即可；用 `scripts/ecs.sh ca` 把 Caddy 的根证书拉回本地并装到自己的设备后不再有提示。

登录由按身份的口令门禁把关：`scripts/ecs.sh auth-init` 为每个演示身份生成随机口令，写入本地与主机的 `~/.config/eab/access.env`（与 model.env 同一模式，不进仓库和数据快照），浏览器里切换身份时输入对应口令。平台按真实客户端地址限制登录频率，每分钟 20 次。跑验收脚本或走查需要免口令时，`scripts/ecs.sh auth off` 临时关闭，跑完 `auth on` 恢复。

阿里云安全组在控制台手动配置，入方向只需要：

| 端口 | 协议 | 用途 |
|---|---|---|
| 22 | TCP | SSH |
| 443 | TCP | https 公网入口 |
| 80 | TCP | 可选，http 跳转到 https |

SSH 隧道仍是备选方式（`scripts/ecs.sh tunnel`，浏览器打开 http://localhost:5173），不想在自己设备上装根证书时可以用它。SSH 使用专用密钥 `~/.ssh/eab_ecs`，主机地址和密钥路径可以用环境变量 `EAB_ECS_HOST`、`EAB_ECS_KEY` 覆盖。

## 脚本

| 脚本 | 在哪里运行 | 作用 |
|---|---|---|
| `deploy/bootstrap.sh` | 主机，root | 安装 Node 24 与 pnpm（走 npmmirror 镜像）、OpenJDK 21，配置 Maven 阿里云镜像，创建运行用户 `eab`，克隆仓库到 `/home/eab/app`。可重复执行 |
| `deploy/remote-deploy.sh` | 主机，root | 拉取指定分支，安装依赖，跑单元测试、lint、类型检查、资产校验，安装并重启 Caddy 与服务，跑端到端测试，验证公网入口与口令门禁，最后做一次数据快照 |
| `deploy/eab-demo.service` | 主机 | systemd 单元，以 `eab` 用户运行 `pnpm demo`，只监听回环，可选加载 model.env 与 access.env |
| `deploy/Caddyfile`、`deploy/caddy.service` | 主机 | 公网入口：Caddy 静态二进制以非 root 用户监听 443，自签证书反代到本机 vite；部署脚本按当前公网 IP 模板化 Caddyfile |
| `pnpm data:backup` | 主机，eab | 用 SQLite 在线备份复制 `data/platform.db` 和 `data/erp.db`，连同签名密钥打包到 `data/backups/`，保留最近 10 份 |
| `scripts/ecs.sh` | 本地 | 封装上面几步：`bootstrap`、`deploy [分支]`、`backup`、`restore <文件>`、`tunnel`、`auth-init`、`auth on\|off`、`open`、`ca` |

## 日常流程

每个阶段合入 `main` 后，在本地运行：

```bash
scripts/ecs.sh deploy main
```

部署和验证完成后，脚本会自动把数据快照拉回本地的 `backups/ecs/`。这个目录含签名私钥，已在 `.gitignore` 里，不进仓库。

## 主机被回收后的恢复

1. 在阿里云控制台重建实例，把 `~/.ssh/eab_ecs.pub` 加到新主机 root 的 `authorized_keys`，安全组重新放行 22 和 443。
2. 公网地址变了的话，设置 `EAB_ECS_HOST`。Caddyfile 在每次部署时按阿里云元数据里的当前公网 IP 重新生成，不需要手动改；浏览器改用新地址访问即可。
3. 依次运行：

```bash
scripts/ecs.sh bootstrap
scripts/ecs.sh deploy main
scripts/ecs.sh restore backups/ecs/<最新的快照>.tar.gz
scripts/ecs.sh auth-init    # 新主机上没有 access.env，重跑一次；本地已生成过口令时沿用不换
```

签名私钥必须和数据库一起恢复。数据库里的标品资产是用这把私钥签的，只恢复数据库、换了新密钥，所有标品资产都会因为验签失败被拒绝加载。如果快照丢了，删掉主机上的 `data/` 目录重新启动即可：平台会生成新密钥，并把仓库里的标品资产重新签名入库，代价是丢失运行期产生的数据。

## 使用真实模型

演示默认使用脚本化模型，不需要任何 Key。要在主机上用真实模型验证，在主机上创建 `/home/eab/.config/eab/model.env`（属主 eab，权限 600），systemd 单元会读取它；这个文件只存在于主机，不进仓库，也不进数据快照。

```bash
EAB_MODEL_PROVIDER=anthropic          # 或 openai（OpenAI 兼容协议）
ANTHROPIC_API_KEY=...                 # openai 时用 OPENAI_API_KEY 和 EAB_OPENAI_BASE_URL
# 可选：档位到模型名的映射，默认 fast=claude-haiku-5-5、reasoning=claude-sonnet-5-5、judge=claude-opus-5-5
EAB_MODEL_REASONING=claude-sonnet-5-5
```

OpenAI 兼容协议的服务（例如 GLM）需要写全三个档位的模型名，`EAB_OPENAI_BASE_URL` 写到 `/v1` 为止：

```bash
EAB_MODEL_PROVIDER=openai
EAB_OPENAI_BASE_URL=https://<网关地址>/v1
OPENAI_API_KEY=...
EAB_MODEL_FAST=glm-5.3-flash
EAB_MODEL_REASONING=glm-5.3
EAB_MODEL_JUDGE=glm-5.3
```

`EAB_MODEL_TIMEOUT_SECONDS` 是单次请求的超时，默认 300 秒；`EAB_MODEL_MAX_RETRIES` 是失败后的重试次数，默认 1 次。厂商模块自带的默认值是 60 秒超时、重试 3 次，推理模型处理大输入时单次常常超过 60 秒，会被反复取消重发，四分钟后才失败。

`EAB_MODEL_MAX_TOKENS` 是单次模型调用的输出上限，默认 16384。带推理过程的模型把推理也算进输出，上限太小时会只有推理没有正文；这时运行以 MODEL_EMPTY_REPLY 失败，并提示调大这个值。

本机用真实模型跑命令行时，把同样的内容放在仓库之外（例如 `~/.config/eab/model.env`），执行前载入环境变量：`set -a; . ~/.config/eab/model.env; set +a`。

修改后执行 `systemctl restart eab-demo`。部署脚本跑端到端测试时会暂时把这个文件改名为 `model.env.paused`，用脚本化模型跑完测试后恢复并重启，脚本中途失败也会恢复。选了真实提供方却缺少 Key 时平台启动失败，日志里说明缺哪个变量，不会静默退回脚本化模型。删除这个文件并重启即回到脚本化模型。

## 公网口令

口令配置在主机 `/home/eab/.config/eab/access.env`（属主 eab，权限 600），由 `scripts/ecs.sh auth-init` 生成，systemd 单元读取。与 model.env 一样，这个文件只存在于本机与演示主机，不进仓库，也不进数据快照；本地留档在 `~/.config/eab/access.env`，口令忘了看这个文件。

```bash
EAB_DEMO_PASSCODES=zhangmin:...,liqiang:...   # user:pass 逗号分隔，* 作为缺省口令
EAB_FORWARD_HEADERS_STRATEGY=native            # Caddy 反代下按 X-Forwarded-For 还原真实客户端地址
EAB_LOGIN_RATE_PER_MINUTE=20                   # 登录限流按真实 IP 每分钟 20 次
```

`EAB_DEMO_PASSCODES` 未设置或为空时，登录回到选人即进入的本地模式，全部本地测试、端到端测试与验收脚本都在这个模式下运行；主机上跑这些脚本前用 `scripts/ecs.sh auth off` 临时关闭，部署脚本在端到端测试窗口也会自动暂停并恢复它。部署结束时脚本会用 access.env 里第一组口令做三态验证（无口令 401、错口令 401、对口令 200），不通过则部署失败。格式非法（缺冒号、空用户名）时平台启动即报错，不会带着半份配置上线。

将来如果想换成受浏览器信任的证书，Let's Encrypt 已开始为 IP 地址签发短期证书；等 Caddy 的自动化支持成熟后把 `tls internal` 换成 ACME 签发即可，其余结构不变。

