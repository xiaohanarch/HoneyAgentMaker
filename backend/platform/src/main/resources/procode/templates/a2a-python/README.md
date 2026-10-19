# {{name}} · A2A 外部 Agent（Python）

启动与接入：

    uv sync
    uv run agent.py              # 默认端口 4300

Agent Card 地址 http://127.0.0.1:4300/.well-known/agent-card.json，
工程清单 eab-project.yaml 里的 cardUrl 已指向本机默认端口。

安全约定：除 Agent Card 外的请求都要带平台签发的服务令牌（EdDSA，受众 eab-a2a），
公钥取平台 /identity/v1/jwks.json，校验失败返回 401，不做匿名降级。

本地调试通过后，在工程目录运行 `pnpm --dir <平台仓库> eab deploy`（`--dir` 可省）：平台读取卡片并把远程 Agent 资产
发布到 tenant.<租户>.remote 命名空间；要被平台 Agent 调用，资产还需声明 {type: agent} 触发器。
