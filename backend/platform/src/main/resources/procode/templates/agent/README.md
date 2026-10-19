# {{name}} · EAB 代码工程（声明式 Agent）

资产是 YAML 文件，进 Git 管理；`pnpm eab` 命令行与 Studio 界面是同一份数据的两种视图。
在界面改或在文件里改都行，push 以本地文件为准覆盖平台草稿。

## 文件

| 文件 | 说明 |
| --- | --- |
| eab-project.yaml | 工程清单：模板、平台地址、登录用户、资产索引 |
| assets/{{name}}.yaml | Agent 资产，唯一事实来源 |
| evals/{{name}}-smoke.yaml | 冒烟评测集，发布前必须通过 |

## 从需求到上线的命令序列

`pnpm eab` 是平台仓库的 pnpm 脚本：在平台仓库里运行时给命令加 `--dir <本工程>`；
在工程目录里运行时用 `pnpm --dir <平台仓库> eab …`，`--dir` 可以省（详见 docs/pro-code.md）。

    pnpm eab login --user <用户>          # 本租户的开发者账号
    pnpm eab validate                    # 逐个资产过 schema 与 lint
    pnpm eab push                        # 建立或更新平台草稿，draftId 回写进清单
    pnpm eab eval                        # 跑冒烟评测集
    pnpm eab deploy --promote test       # 发布到 dev 并晋级 test；上 prod 需再晋级
    pnpm eab logs --agent {{name}}       # 查看运行记录与追踪

把 `<改我>` 占位（租户编号、显示名、指令、示例话术）换成真实内容后再 push。
