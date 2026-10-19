# {{name}} · EAB 代码工程（技能）

这个工程由 `pnpm eab pull` 从平台草稿拉出，或由 Studio 的“导出为代码工程”生成。
技能资产与 Studio 草稿是同一份数据的两种视图，push 以本地文件为准。

## 文件

| 文件 | 说明 |
| --- | --- |
| eab-project.yaml | 工程清单：模板、平台地址、登录用户、资产索引 |
| skills/{{name}}.yaml | 技能资产，唯一事实来源 |

## 改动后推回平台

`pnpm eab` 是平台仓库的 pnpm 脚本：在平台仓库里运行时给命令加 `--dir <本工程>`；
在工程目录里运行时用 `pnpm --dir <平台仓库> eab …`，`--dir` 可以省（详见 docs/pro-code.md）。

    pnpm eab login --user <用户>
    pnpm eab validate
    pnpm eab push          # 建立或更新技能草稿，draftId 回写进清单
    pnpm eab deploy        # 发布技能
