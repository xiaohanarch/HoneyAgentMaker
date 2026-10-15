package dev.eab.platform;

/** 演示模式开关：开放选人登录、HTTP 令牌交换、代签 Agent 令牌等接口，只允许监听本机回环地址 */
public record DemoMode(boolean enabled) {}
