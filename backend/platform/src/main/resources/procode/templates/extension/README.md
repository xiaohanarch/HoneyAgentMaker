# {{name}} · EAB 代码工程（标品 Agent 租户扩展）

对标品 Agent {{target}} 做租户扩展：本公司规定、额外工具与技能、确认策略都写在扩展资产里。
发布即对全租户生效（不经环境晋级），发布前标品与扩展的评测集都要通过。

## 文件

| 文件 | 说明 |
| --- | --- |
| eab-project.yaml | 工程清单：模板、平台地址、登录用户、资产索引 |
| assets/{{name}}.yaml | 扩展资产，target 指向 {{target}} |
| evals/{{name}}-smoke.yaml | 扩展冒烟评测集，发布门禁要求通过 |
| skills/{{name}}-skill.yaml | 技能骨架，按需改名并填充步骤 |

## 从需求到上线的命令序列

`pnpm eab` 是平台仓库的 pnpm 脚本：在平台仓库里运行时给命令加 `--dir <本工程>`；
在工程目录里运行时用 `pnpm --dir <平台仓库> eab …`，`--dir` 可以省（详见 docs/pro-code.md）。

    pnpm eab login --user <用户>          # 本租户的开发者账号
    pnpm eab validate                    # 逐个资产过 schema 与 lint
    pnpm eab push                        # 扩展与评测集随同一条草稿保存，draftId 回写进清单
    pnpm eab eval                        # 跑扩展评测集；评测里确认点一律自动拒绝
    pnpm eab deploy                      # 发布即生效，不需要环境晋级
    pnpm eab logs --agent {{targetName}}

开发建议：先用 Studio MCP 查语义（semantic_describe_object、semantic_find_rules）与扩展点
（studio_list_vendor_agents、studio_get_vendor_agent），确认 BO 操作与规则 id 后再写扩展；
硬约束优先写成可检查的语义规则，而不是只写在指令里。
