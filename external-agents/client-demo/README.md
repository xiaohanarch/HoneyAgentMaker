# client-demo：外部平台调用平台的采购助手

模拟“其他平台的 Agent”用 A2A 官方 JS SDK 调用平台里对外暴露的采购助手：

1. 不带用户令牌调用一次，平台返回 401；
2. 以张敏的身份（演示模式下选人登录取令牌）提问，经采购助手的路由和四处鉴权得到结果；
3. 请求头 `X-A2A-Caller` 写明调用方，治理中心的审计里能看到这次入站调用。

```
pnpm --filter @eab/client-demo run start [问题]   # EAB_PLATFORM_URL 默认 http://127.0.0.1:4000
```
